package ma.mystix.clearance;

/**
 * Submits an invoice to the tax administration for clearance (ADR-0001).
 * <p>
 * TODO(DGI-SPEC): the DGI API, its payload, its responses and its identifiers are not published. The only
 * implementation is {@link SimulatedClearanceGateway}; a real adapter (Lot 9) implements this interface without
 * touching callers.
 */
public interface ClearanceGateway {

    ClearanceResult submit(ClearanceRequest request);

    /** True for any implementation whose answers are not issued by the DGI. */
    boolean simulated();
}
