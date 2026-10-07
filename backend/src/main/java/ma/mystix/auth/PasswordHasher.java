package ma.mystix.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing from the JDK (no extra dependency). The iteration count is stored with each
 * hash, so it can be raised later without invalidating existing passwords. Comparison is constant-time.
 */
final class PasswordHasher {

    /** OWASP Password Storage Cheat Sheet recommendation for PBKDF2-HMAC-SHA256. */
    static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PasswordHasher() {
    }

    static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + "$" + ITERATIONS + "$" + B64.encodeToString(salt) + "$"
                + B64.encodeToString(derive(password, salt, ITERATIONS));
    }

    static boolean verify(char[] password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        byte[] expected = B64D.decode(parts[3]);
        byte[] actual = derive(password, B64D.decode(parts[2]), Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** A valid hash of a random password, used to spend the same time when the e-mail is unknown. */
    static final String DUMMY = hash(randomChars());

    private static char[] randomChars() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        char[] chars = B64.encodeToString(bytes).toCharArray();
        Arrays.fill(bytes, (byte) 0);
        return chars;
    }
}
