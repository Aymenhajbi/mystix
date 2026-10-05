-- Per-company setting: the seller ICE of every submitted invoice is the company's own ICE (default),
-- unless the client environment turns the rule off (e.g. an integrator submitting for several entities).
ALTER TABLE company
    ADD COLUMN enforce_seller_ice BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN company.enforce_seller_ice IS
    'TRUE: missing seller ICE is set to the company ICE, a different one is rejected (SELLER_ICE_MISMATCH)';
