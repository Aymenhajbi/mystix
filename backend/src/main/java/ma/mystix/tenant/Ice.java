package ma.mystix.tenant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifiant Commun de l'Entreprise: 15 digits.
 * <p>
 * Only the format is checked. TODO(DGI-SPEC): the control-key algorithm of the last two digits is not
 * published in an official technical specification; add it as a configurable check once it is.
 */
public record Ice(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[0-9]{15}$");

    public Ice {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("ICE must be exactly 15 digits");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && FORMAT.matcher(candidate).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
