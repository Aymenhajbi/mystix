package ma.mystix.stock;

/**
 * The five states that partition the stock of an item at a location (ADR-0011).
 * On-hand = AVAILABLE + RESERVED + QUARANTINE; available to promise (ATP) = AVAILABLE + IN_TRANSIT.
 */
public enum StockState {
    /** Free to sell or reserve. */
    AVAILABLE,
    /** Reserved by a customer order, still in the warehouse. */
    RESERVED,
    /** Announced by a despatch advice, not yet received. */
    IN_TRANSIT,
    /** Blocked: quality hold, damaged, refused at receipt. */
    QUARANTINE,
    /** Owned stock held at a customer's site. */
    CONSIGNMENT
}
