-- Processing log of a company: every submission outcome (accepted, replayed, rejected with its reasons) and every
-- clearance answer. Append-only. Rejected submissions are only traced here (they create no invoice).
CREATE TABLE processing_log (
    id             UUID          PRIMARY KEY,
    seq            BIGSERIAL     NOT NULL,
    company_id     UUID          NOT NULL REFERENCES company (id),
    occurred_at    TIMESTAMPTZ   NOT NULL,
    level          VARCHAR(8)    NOT NULL,
    stage          VARCHAR(20)   NOT NULL,
    event          VARCHAR(40)   NOT NULL,
    error_code     VARCHAR(60),
    invoice_number VARCHAR(100),
    invoice_id     UUID          REFERENCES invoice_message (id),
    request_id     VARCHAR(64),
    message        VARCHAR(2000) NOT NULL,
    details        JSONB,
    payload        BYTEA,
    CONSTRAINT ck_processing_log_level CHECK (level IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX ix_processing_log_company_seq ON processing_log (company_id, seq DESC);
CREATE INDEX ix_processing_log_invoice ON processing_log (company_id, invoice_id);
