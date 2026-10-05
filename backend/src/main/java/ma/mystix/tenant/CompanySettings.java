package ma.mystix.tenant;

/**
 * Settings of a client environment, changed from the portal configurator. Both rules are on by default.
 *
 * @param enforceSellerIce the seller of a submitted invoice is the company itself (ADR-0009)
 * @param enforceVatRates  a standard-rated line must carry a rate in force on the issue date, according to the
 *                         dated VAT referential (ADR-0010)
 */
public record CompanySettings(boolean enforceSellerIce, boolean enforceVatRates) {
}
