-- Clearance (ADR-0001). Only a SIMULATED gateway exists: references are fictitious and flagged as such.
ALTER TABLE invoice_message DROP CONSTRAINT ck_invoice_message_status;
ALTER TABLE invoice_message ADD CONSTRAINT ck_invoice_message_status
    CHECK (status IN ('VALIDATED', 'CLEARED', 'CLEARANCE_REJECTED'));

ALTER TABLE invoice_message ADD COLUMN clearance_reference VARCHAR(100);
ALTER TABLE invoice_message ADD COLUMN clearance_simulated BOOLEAN;
ALTER TABLE invoice_message ADD COLUMN clearance_at        TIMESTAMPTZ;

-- Append-only status history (audit trail).
CREATE TABLE invoice_status_event (
    id          UUID          PRIMARY KEY,
    seq         BIGSERIAL     NOT NULL,
    message_id  UUID          NOT NULL REFERENCES invoice_message (id),
    company_id  UUID          NOT NULL REFERENCES company (id),
    status      VARCHAR(30)   NOT NULL,
    detail      VARCHAR(1000),
    occurred_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_invoice_status_event_message ON invoice_status_event (company_id, message_id, seq);
