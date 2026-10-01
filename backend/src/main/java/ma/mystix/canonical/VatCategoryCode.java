package ma.mystix.canonical;

/**
 * BT-151 / BT-118 VAT category code, UNTDID 5305 subset.
 * <p>
 * TODO(DGI-SPEC): the mapping between Moroccan VAT regimes (taxable, exempt with or without deduction right,
 * outside scope) and these codes is not published; it is configured, not inferred.
 */
public enum VatCategoryCode {
    /** Standard rate. */
    S,
    /** Zero rated goods. */
    Z,
    /** Exempt from tax. */
    E,
    /** Services outside scope of tax. */
    O
}
