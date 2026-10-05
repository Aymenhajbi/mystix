-- Backdated invoices wait for a validation by the environment administrator before clearance (ADR-0010).
-- PENDING_VALIDATION -> VALIDATED (approved, then clearance) or VALIDATION_REJECTED.
ALTER TABLE invoice_message DROP CONSTRAINT ck_invoice_message_status;
ALTER TABLE invoice_message ADD CONSTRAINT ck_invoice_message_status
    CHECK (status IN ('PENDING_VALIDATION', 'VALIDATED', 'VALIDATION_REJECTED', 'CLEARED', 'CLEARANCE_REJECTED'));
