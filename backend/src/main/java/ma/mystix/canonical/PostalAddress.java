package ma.mystix.canonical;

import java.util.Objects;

/**
 * BG-5 / BG-8 postal address.
 *
 * @param street      BT-35 / BT-50 address line 1, optional
 * @param city        BT-37 / BT-52 city, optional
 * @param postalCode  BT-38 / BT-53 post code, optional
 * @param countryCode BT-40 / BT-55 ISO 3166-1 alpha-2 country code
 */
public record PostalAddress(String street, String city, String postalCode, String countryCode) {

    public PostalAddress {
        Objects.requireNonNull(countryCode, "countryCode");
        if (!countryCode.matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException("countryCode must be ISO 3166-1 alpha-2");
        }
    }
}
