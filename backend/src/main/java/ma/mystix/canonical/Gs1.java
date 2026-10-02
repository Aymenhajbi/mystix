package ma.mystix.canonical;

/** GS1 identifier checks (GTIN, GLN, SSCC): fixed length and modulo 10 check digit. */
public final class Gs1 {

    private Gs1() {
    }

    public static boolean isValidGln(String value) {
        return hasValidCheckDigit(value, 13);
    }

    public static boolean isValidGtin(String value) {
        return value != null
                && (value.length() == 8 || value.length() == 12 || value.length() == 13 || value.length() == 14)
                && hasValidCheckDigit(value, value.length());
    }

    public static boolean isValidSscc(String value) {
        return hasValidCheckDigit(value, 18);
    }

    private static boolean hasValidCheckDigit(String value, int length) {
        if (value == null || value.length() != length || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        int sum = 0;
        // Weights alternate 3, 1, 3... starting from the digit just left of the check digit.
        for (int i = length - 2, weight = 3; i >= 0; i--, weight = 4 - weight) {
            sum += (value.charAt(i) - '0') * weight;
        }
        int expected = (10 - sum % 10) % 10;
        return expected == value.charAt(length - 1) - '0';
    }
}
