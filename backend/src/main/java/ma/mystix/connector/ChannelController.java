package ma.mystix.connector;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import ma.mystix.auth.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Communication channels of the client environment named by {@code X-Mystix-Company-Id} (ADR-0015). The same API
 * serves the Mystix portal and Atelys; {@code ?app=MYSTIX|ATELYS} narrows the list to one application.
 * Creating, changing and deleting a channel is an administrator action.
 */
@RestController
@RequestMapping(value = "/api/v1/channels", produces = MediaType.APPLICATION_JSON_VALUE)
class ChannelController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final ChannelService channels;
    private final CertificateService certificates;
    private final Access access;
    private final String as2InboundUrl;

    ChannelController(ChannelService channels, CertificateService certificates, Access access,
                      @Value("${mystix.connector.as2.inbound-url:}") String as2InboundUrl) {
        this.channels = channels;
        this.certificates = certificates;
        this.access = access;
        this.as2InboundUrl = as2InboundUrl == null || as2InboundUrl.isBlank() ? null : as2InboundUrl.strip();
    }

    record CertificateRequest(CertificateService.Role role, String certificate) {
    }

    record ChannelRequest(String name, Channel.Protocol protocol, Channel.Direction direction, UUID partnerId,
                          Boolean usedByMystix, Boolean usedByAtelys, Channel.Status status,
                          Map<String, Object> settings, String credentialRef) {

        ChannelService.Config config() {
            return new ChannelService.Config(name, protocol, direction, partnerId,
                    usedByMystix == null || usedByMystix, Boolean.TRUE.equals(usedByAtelys), status, settings,
                    credentialRef);
        }
    }

    record FieldView(String key, String kind, String group, boolean required, Object defaultValue,
                     List<String> choices) {
    }

    record ProtocolView(String code, List<FieldView> fields, boolean needsCredential) {
    }

    /**
     * @param as2InboundUrl where partners send AS2 messages to Mystix, for the exchange sheet; null until the AS2
     *                      transport is deployed and mystix.connector.as2.inbound-url is set
     */
    record CatalogView(List<ProtocolView> protocols, List<String> directions, List<String> statuses,
                       String transport, String as2InboundUrl) {
    }

    /** What the configuration form needs: the parameters of each protocol, with their defaults. */
    @GetMapping("/catalog")
    CatalogView catalog() {
        List<ProtocolView> protocols = Arrays.stream(Channel.Protocol.values())
                .map(p -> new ProtocolView(p.name(), ChannelSettings.FIELDS.get(p).stream()
                        .map(f -> new FieldView(f.key(), f.kind().name(), ChannelSettings.groupOf(f.key()).name(),
                                f.required(), f.defaultValue(), f.choices()))
                        .toList(), p != Channel.Protocol.WEBHOOK && p != Channel.Protocol.HTTPS))
                .toList();
        // TODO(lot-6): AS2, SFTP and FTP/FTPS transports. Until then a channel is a stored, checked configuration.
        return new CatalogView(protocols, Arrays.stream(Channel.Direction.values()).map(Enum::name).toList(),
                Arrays.stream(Channel.Status.values()).map(Enum::name).toList(), "CONFIGURATION_ONLY", as2InboundUrl);
    }

    /** Every certificate of the environment, soonest expiry first. */
    @GetMapping("/certificates")
    List<CertificateService.Certificate> allCertificates(@RequestHeader(COMPANY) UUID companyId) {
        return certificates.all(companyId);
    }

    @GetMapping("/{id}/certificates")
    List<CertificateService.Certificate> certificates(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return certificates.list(companyId, id);
    }

    @PostMapping(value = "/{id}/certificates", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CertificateService.Certificate> addCertificate(@RequestHeader(COMPANY) UUID companyId,
                                                                  @PathVariable UUID id,
                                                                  @RequestBody CertificateRequest r,
                                                                  HttpServletRequest request) {
        access.requireManager(request, companyId);
        return ResponseEntity.status(201)
                .body(certificates.add(companyId, id, r.role(), r.certificate(), access.actor(request)));
    }

    @DeleteMapping("/{id}/certificates/{certificateId}")
    ResponseEntity<Void> deleteCertificate(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                           @PathVariable UUID certificateId, HttpServletRequest request) {
        access.requireManager(request, companyId);
        certificates.delete(companyId, id, certificateId, access.actor(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    List<Channel> list(@RequestHeader(COMPANY) UUID companyId,
                       @RequestParam(required = false) ChannelService.App app) {
        return channels.list(companyId, app);
    }

    @GetMapping("/{id}")
    Channel get(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return channels.get(companyId, id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Channel> create(@RequestHeader(COMPANY) UUID companyId, @RequestBody ChannelRequest r,
                                   HttpServletRequest request) {
        access.requireManager(request, companyId);
        return ResponseEntity.status(201).body(channels.create(companyId, r.config(), access.actor(request)));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Channel update(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, @RequestBody ChannelRequest r,
                   HttpServletRequest request) {
        access.requireManager(request, companyId);
        return channels.update(companyId, id, r.config(), access.actor(request));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                HttpServletRequest request) {
        access.requireManager(request, companyId);
        channels.delete(companyId, id, access.actor(request));
        return ResponseEntity.noContent().build();
    }
}
