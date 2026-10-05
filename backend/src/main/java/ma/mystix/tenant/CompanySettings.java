package ma.mystix.tenant;

/**
 * Settings of a client environment, changed from the portal configurator.
 *
 * @param enforceSellerIce when {@code true} (default), the seller of a submitted invoice is the company itself:
 *                         a missing seller ICE is set to the company ICE and a different one is rejected
 */
public record CompanySettings(boolean enforceSellerIce) {
}
