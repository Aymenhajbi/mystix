package ma.mystix.connector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.UnresolvedAddressException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import ma.mystix.flow.ExchangeFlow;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.MystixException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SFTP polling listener (ADR-0016): Mystix connects, as a client, to the partner's SFTP server and collects the files
 * of the inbound directory. Not to be confused with an SFTP server hosted by Mystix (not built).
 * <p>
 * Each cycle: connect (the server key must match the declared SHA-256 fingerprint, never skipped), authenticate with
 * the secret named by the channel, list the directory, take the files that match the pattern, are complete (no
 * .part/.tmp/.filepart suffix, unchanged for {@code sftpStableAfter}) and within the size limit, hash them, skip a
 * content already acquired by this listener, hand the others to the entry point, then delete, move or keep the remote
 * file as the channel says. A refused or rejected file stays where it is, and is not taken again (same content).
 */
@Component
class SftpPollingAdapter implements ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SftpPollingAdapter.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> PARTIAL_SUFFIXES = Set.of(".part", ".tmp", ".filepart", ".partial");

    /** Everything one listener needs to poll; the secret lives only here, in memory. */
    record Config(UUID listenerId, UUID companyId, UUID flowId, String host, int port, String username,
                  String authMethod, String secret, String secretRef, String fingerprint, String directory,
                  Pattern pattern, String afterDownload, String archiveDirectory, int maxBytes, int pollSeconds) {
    }

    /** A started poller: its schedule and the health observed at its last cycle. */
    static final class Poller {
        final Config config;
        volatile ScheduledFuture<?> schedule;
        volatile Health lastHealth = Health.ok();
        final Object cycle = new Object();

        Poller(Config config) {
            this.config = config;
        }
    }

    private final ListenerStore store;
    private final EntryPointDispatcher dispatcher;
    private final SecretStore secrets;
    private final ConnectivityProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            r -> Thread.ofPlatform().name("sftp-poller").daemon().unstarted(r));

    SftpPollingAdapter(ListenerStore store, EntryPointDispatcher dispatcher, SecretStore secrets,
                       ConnectivityProperties properties) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.secrets = secrets;
        this.properties = properties;
    }

    @Override
    public Listener.Kind kind() {
        return Listener.Kind.SFTP_POLLING;
    }

    /** One poller per listener across processes: two would move the same remote files. */
    @Override
    public boolean multiInstance() {
        return false;
    }

    @Override
    public List<ApiError.FieldViolation> preconditions(Context c) {
        List<ApiError.FieldViolation> problems = new ArrayList<>();
        Channel channel = c.channel();
        Map<String, Object> s = channel.settings();
        if (channel.protocol() != Channel.Protocol.SFTP) {
            problems.add(new ApiError.FieldViolation("channel.protocol", "SFTP required to poll an SFTP server"));
        }
        if (channel.direction() == Channel.Direction.OUT) {
            problems.add(new ApiError.FieldViolation("channel.direction", "IN or BOTH required to receive"));
        }
        if (channel.status() != Channel.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("channel.status", "the channel must be ACTIVE"));
        }
        if (channel.protocol() == Channel.Protocol.SFTP) {
            if (text(s, "inboundDirectory") == null) {
                problems.add(new ApiError.FieldViolation("channel.inboundDirectory", "the directory to poll is required"));
            }
            if (text(s, "hostKeyFingerprint") == null) {
                problems.add(new ApiError.FieldViolation("channel.hostKeyFingerprint",
                        "the server key fingerprint (SHA256:…) is required; it is always checked"));
            }
            if ("MOVE".equals(text(s, "afterDownload")) && text(s, "archiveDirectory") == null) {
                problems.add(new ApiError.FieldViolation("channel.archiveDirectory", "required to move files after download"));
            }
            if (channel.credentialRef() == null) {
                problems.add(new ApiError.FieldViolation("channel.credentialRef", "no secret reference on the channel"));
            } else if (secrets.read(channel.credentialRef()).isEmpty()) {
                problems.add(new ApiError.FieldViolation("secret", "secret " + channel.credentialRef()
                        + " not found in the secret store"));
            }
        }
        EntryPoint entry = c.entryPoint();
        if (!entry.enabled()) {
            problems.add(new ApiError.FieldViolation("entryPoint.enabled", "the entry point is disabled"));
        }
        if (!entry.dispatchable()) {
            problems.add(new ApiError.FieldViolation("entryPoint.inputFormat", entry.documentType() + " in "
                    + entry.inputFormat() + " cannot be handed to the pipeline yet; JSON invoices can"));
        }
        if (c.flow().status() != ExchangeFlow.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("entryPoint.flow", "the flow " + c.flow().name() + " is not ACTIVE"));
        }
        HttpInboundAdapter.maxBytes(c.listener().settings(), problems);
        return problems;
    }

    /** Connects once to prove the configuration (key, credentials, directory), then schedules the polls. */
    @Override
    public Handle start(Context c) {
        Config config = config(c);
        withSftp(config, sftp -> {
            if (!sftp.stat(config.directory()).isDirectory()) {
                throw new StartFailure("REMOTE_DIRECTORY_UNAVAILABLE", config.directory() + " is not a directory", null);
            }
            return null;
        });
        Poller poller = new Poller(config);
        long period = TimeUnit.SECONDS.toMillis(config.pollSeconds());
        poller.schedule = scheduler.scheduleWithFixedDelay(() -> safeCycle(poller), period, period, TimeUnit.MILLISECONDS);
        return new Handle(kind(), config.listenerId(), config.companyId(), poller);
    }

    @Override
    public void stop(Handle handle) {
        Poller poller = (Poller) handle.resource();
        if (poller != null && poller.schedule != null) {
            poller.schedule.cancel(false);
        }
        if (poller != null) {
            // A cycle under way finishes its current file; no new cycle starts.
            synchronized (poller.cycle) {
                poller.schedule = null;
            }
        }
    }

    @Override
    public Health health(Handle handle) {
        Poller poller = (Poller) handle.resource();
        if (poller == null || poller.schedule == null || poller.schedule.isCancelled()) {
            return new Health(false, "POLLER_STOPPED", "the poller is not scheduled in this process");
        }
        return poller.lastHealth;
    }

    @Override
    public PollResult pollNow(Handle handle) {
        return cycle((Poller) handle.resource());
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }

    private void safeCycle(Poller poller) {
        try {
            cycle(poller);
        } catch (RuntimeException e) {
            LOG.warn("SFTP poll of listener {} failed: {}", poller.config.listenerId(), e.getMessage());
        }
    }

    /** One acquisition cycle; its outcome becomes the health the heartbeat reports. */
    PollResult cycle(Poller poller) {
        synchronized (poller.cycle) {
            Config config = poller.config;
            try {
                PollResult result = withSftp(config, sftp -> collect(sftp, config));
                poller.lastHealth = Health.ok();
                return result;
            } catch (StartFailure e) {
                poller.lastHealth = new Health(false, e.code(), e.getMessage());
                throw e;
            }
        }
    }

    private PollResult collect(SftpClient sftp, Config config) throws IOException {
        int seen = 0;
        int accepted = 0;
        int rejected = 0;
        int refused = 0;
        int duplicates = 0;
        int skipped = 0;
        Instant stableBefore = Instant.now().minus(properties.sftpStableAfter());
        List<SftpClient.DirEntry> entries = new ArrayList<>();
        for (SftpClient.DirEntry entry : directory(sftp, config)) {
            entries.add(entry);
        }
        entries.sort(SftpClient.DirEntry.BY_CASE_SENSITIVE_FILENAME);
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            SftpClient.Attributes attributes = entry.getAttributes();
            if (!attributes.isRegularFile() || !config.pattern().matcher(name).matches()) {
                continue;
            }
            seen++;
            String path = join(config.directory(), name);
            if (partial(name) || (attributes.getModifyTime() != null
                    && attributes.getModifyTime().toInstant().isAfter(stableBefore))) {
                skipped++;
                continue;
            }
            Download download = download(sftp, path, config.maxBytes());
            UUID correlation = UUID.randomUUID();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("transport", "SFTP");
            source.put("file", path);
            var before = store.fileOutcome(config.listenerId(), download.sha256());
            if (before.isPresent()) {
                // Already acquired: never handed to the pipeline twice. An accepted content is cleared away like
                // the first copy; a refused or rejected one stays for someone to look at, and is journaled once.
                if ("ACCEPTED".equals(before.get())) {
                    duplicates++;
                    store.event(config.companyId(), config.listenerId(), "MESSAGE_DUPLICATE", "INFO", correlation,
                            Map.of("file", path, "sha256", download.sha256()), null);
                    afterDownload(sftp, config, path, name, correlation);
                } else {
                    skipped++;
                }
                continue;
            }
            if (download.tooLarge()) {
                refused++;
                dispatcher.refuse(config.companyId(), config.listenerId(), correlation, "INBOUND_TOO_LARGE",
                        "more than " + config.maxBytes() + " bytes", source);
                store.recordFile(config.companyId(), config.listenerId(), path, download.size(), download.sha256(),
                        "REFUSED", correlation, null, "INBOUND_TOO_LARGE");
                continue;
            }
            try {
                var submission = dispatcher.dispatch(config.companyId(), config.listenerId(), config.flowId(),
                        download.bytes(), correlation, source);
                store.recordFile(config.companyId(), config.listenerId(), path, download.size(), download.sha256(),
                        "ACCEPTED", correlation, submission.invoice().id(), null);
                accepted++;
                afterDownload(sftp, config, path, name, correlation);
            } catch (MystixException e) {
                store.recordFile(config.companyId(), config.listenerId(), path, download.size(), download.sha256(),
                        "REJECTED", correlation, null, e.errorCode().name());
                rejected++;
            }
        }
        return new PollResult(seen, accepted, rejected, refused, duplicates, skipped);
    }

    private void afterDownload(SftpClient sftp, Config config, String path, String name, UUID correlation)
            throws IOException {
        switch (config.afterDownload()) {
            case "DELETE" -> sftp.remove(path);
            case "MOVE" -> {
                String target = join(config.archiveDirectory(), name);
                try {
                    sftp.rename(path, target);
                } catch (SftpException e) {
                    // Same name already archived: keep both, the new one tagged with its correlation.
                    sftp.rename(path, target + "." + correlation.toString().substring(0, 8));
                }
            }
            default -> {
                // KEEP: the content hash stops a second acquisition.
            }
        }
    }

    private record Download(byte[] bytes, long size, String sha256, boolean tooLarge) {
    }

    /** Reads the whole file to hash it, keeping at most {@code maxBytes} in memory. */
    private static Download download(SftpClient sftp, String path, int maxBytes) throws IOException {
        MessageDigest digest = sha256();
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long size = 0;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream in = sftp.read(path)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                if (size + n <= maxBytes) {
                    kept.write(buffer, 0, n);
                }
                size += n;
            }
        }
        return new Download(kept.toByteArray(), size, HexFormat.of().formatHex(digest.digest()), size > maxBytes);
    }

    private interface SftpWork<T> {
        T run(SftpClient sftp) throws IOException;
    }

    /**
     * Opens a session with the server key checked against the declared fingerprint, authenticates, runs the work.
     * Failures come out as {@link StartFailure} with an observed code.
     */
    private <T> T withSftp(Config config, SftpWork<T> work) {
        AtomicReference<String> presented = new AtomicReference<>();
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, address, key) -> {
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
            presented.set(fingerprint);
            return fingerprint.equals(config.fingerprint());
        });
        client.start();
        try (ClientSession session = connect(client, config, presented)) {
            authenticate(session, config, presented);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return work.run(sftp);
            }
        } catch (StartFailure e) {
            throw e;
        } catch (SftpException e) {
            throw new StartFailure(e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE
                    || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH ? "REMOTE_DIRECTORY_UNAVAILABLE"
                    : e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED ? "REMOTE_PERMISSION_DENIED"
                    : "SFTP_ERROR", e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new StartFailure("SFTP_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            client.stop();
        }
    }

    private static ClientSession connect(SshClient client, Config config, AtomicReference<String> presented) {
        try {
            return client.connect(config.username(), config.host(), config.port()).verify(CONNECT_TIMEOUT).getSession();
        } catch (IOException | RuntimeException e) {
            String fingerprint = presented.get();
            if (fingerprint != null && !fingerprint.equals(config.fingerprint())) {
                throw new StartFailure("HOST_KEY_MISMATCH", "server presented " + fingerprint + ", expected "
                        + config.fingerprint(), e);
            }
            Throwable root = root(e);
            boolean timeout = root instanceof java.util.concurrent.TimeoutException
                    || String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("timeout");
            // No SSH handshake started (no server key seen): the host did not answer or refused the connection.
            String code = timeout ? "TIMEOUT"
                    : fingerprint == null || root instanceof ConnectException || root instanceof UnknownHostException
                    || root instanceof UnresolvedAddressException ? "HOST_UNREACHABLE" : "CONNECT_FAILED";
            throw new StartFailure(code, config.host() + ":" + config.port() + " " + root.getClass().getSimpleName()
                    + ": " + root.getMessage(), e);
        }
    }

    private void authenticate(ClientSession session, Config config, AtomicReference<String> presented)
            throws IOException {
        if ("PASSWORD".equals(config.authMethod())) {
            session.addPasswordIdentity(config.secret());
        } else {
            try {
                Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null,
                        NamedResource.ofName(config.secretRef()),
                        new ByteArrayInputStream(config.secret().getBytes(StandardCharsets.UTF_8)),
                        FilePasswordProvider.EMPTY);
                if (keys == null || !keys.iterator().hasNext()) {
                    throw new StartFailure("SECRET_INVALID", "the secret " + config.secretRef()
                            + " is not a private key (passphrase-protected keys are not supported yet)", null);
                }
                keys.forEach(session::addPublicKeyIdentity);
            } catch (java.security.GeneralSecurityException | IOException e) {
                throw new StartFailure("SECRET_INVALID", "the secret " + config.secretRef()
                        + " is not a readable private key", e);
            }
        }
        try {
            session.auth().verify(CONNECT_TIMEOUT);
        } catch (IOException | RuntimeException e) {
            String fingerprint = presented.get();
            if (fingerprint != null && !fingerprint.equals(config.fingerprint())) {
                throw new StartFailure("HOST_KEY_MISMATCH", "server presented " + fingerprint + ", expected "
                        + config.fingerprint(), e);
            }
            throw new StartFailure("AUTH_FAILED", "authentication of " + config.username() + " refused", e);
        }
    }

    private static Iterable<SftpClient.DirEntry> directory(SftpClient sftp, Config config) throws IOException {
        return sftp.readDir(config.directory());
    }

    private Config config(Context c) {
        Map<String, Object> s = c.channel().settings();
        String fingerprint = text(s, "hostKeyFingerprint");
        String secret = secrets.read(c.channel().credentialRef())
                .orElseThrow(() -> new StartFailure("SECRET_MISSING", "secret " + c.channel().credentialRef()
                        + " not found", null));
        return new Config(c.listener().id(), c.listener().companyId(), c.flow().id(), text(s, "host"),
                ((Number) s.getOrDefault("port", 22)).intValue(), text(s, "username"),
                String.valueOf(s.getOrDefault("authMethod", "PRIVATE_KEY")), secret, c.channel().credentialRef(),
                fingerprint.startsWith("SHA256:") ? fingerprint : "SHA256:" + fingerprint,
                text(s, "inboundDirectory"), glob(String.valueOf(s.getOrDefault("filePattern", "*"))),
                String.valueOf(s.getOrDefault("afterDownload", "MOVE")), text(s, "archiveDirectory"),
                HttpInboundAdapter.maxBytes(c.listener().settings(), new ArrayList<>()),
                ((Number) s.getOrDefault("pollSeconds", 300)).intValue());
    }

    /** {@code *} any characters, {@code ?} one; everything else literal. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char ch : glob.toCharArray()) {
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static boolean partial(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return PARTIAL_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    private static String text(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Throwable root(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
