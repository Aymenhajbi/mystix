package ma.mystix.connector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the secret a channel names by {@code credential_ref} (ADR-0015): a password or a private key, never stored
 * in the database. This implementation reads one file per reference from {@code mystix.secrets.directory}, outside
 * the repository; it is meant for development and single-server installs.
 * TODO(lot-8): Vault (or the secret manager retained for operations) behind the same contract.
 */
@Component
class SecretStore {

    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._:/-]{1,120}$");

    private final Path directory;

    SecretStore(@Value("${mystix.secrets.directory:}") String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    boolean configured() {
        return directory != null;
    }

    /** The secret named by {@code ref}, if the store has it. The reference never escapes the directory. */
    Optional<String> read(String ref) {
        if (directory == null || ref == null || !REF.matcher(ref).matches()) {
            return Optional.empty();
        }
        Path file = directory.resolve(ref.replace(':', '_')).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            throw new UncheckedIOException("Secret " + ref + " unreadable", e);
        }
    }
}
