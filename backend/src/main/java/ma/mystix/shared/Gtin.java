package ma.mystix.shared;

import java.util.Optional;

/**
 * A GS1 trade item number (GTIN-8, -12, -13 or -14) with a verified check digit (GS1 mod 10), kept as 14 digits.
 * The prefix only tells which GS1 organisation issued the number, never the country of origin of the product.
 */
public record Gtin(String gtin14, int length) {

    /** Prefixes this platform reads; anything else is OTHER. Source: gs1.org/prefixes. */
    public enum Prefix { GS1_MOROCCO, RESTRICTED_CIRCULATION, OTHER }

    public static Optional<Gtin> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replace(" ", "").replace("-", "");
        int n = digits.length();
        if ((n != 8 && n != 12 && n != 13 && n != 14) || !digits.chars().allMatch(Character::isDigit)
                || !checkDigitValid(digits)) {
            return Optional.empty();
        }
        return Optional.of(new Gtin("0".repeat(14 - n) + digits, n));
    }

    public static boolean checkDigitValid(String digits) {
        return digits.length() > 1 && checkDigit(digits.substring(0, digits.length() - 1))
                == digits.charAt(digits.length() - 1) - '0';
    }

    /** GS1 mod 10: weights 3 and 1 from the rightmost digit of the payload. */
    public static int checkDigit(String payload) {
        int sum = 0;
        for (int i = 0; i < payload.length(); i++) {
            int digit = payload.charAt(payload.length() - 1 - i) - '0';
            sum += i % 2 == 0 ? digit * 3 : digit;
        }
        return (10 - sum % 10) % 10;
    }

    /** The GTIN-13 view (leading zero removed) when the number has 13 digits or fewer. */
    public String display() {
        return gtin14.startsWith("0") ? gtin14.substring(1) : gtin14;
    }

    public Prefix prefix() {
        String ean13 = gtin14.substring(1);
        if (gtin14.startsWith("0") && ean13.startsWith("611")) {
            return Prefix.GS1_MOROCCO;
        }
        // RCN-13: prefixes 20 to 29 (and 02 in GTIN-13 form), defined by the local GS1 organisation.
        if (gtin14.startsWith("0") && (ean13.charAt(0) == '2' || ean13.startsWith("02"))) {
            return Prefix.RESTRICTED_CIRCULATION;
        }
        return Prefix.OTHER;
    }
}
