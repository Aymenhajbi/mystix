-- EDIFACT stock messages as received (ADR-0012): exact bytes and SHA-256, outcome, link to the stock event.
CREATE TABLE stock_edi_message (
    id                    UUID          PRIMARY KEY,
    company_id            UUID          NOT NULL REFERENCES company (id),
    event_id              UUID          REFERENCES stock_event (id),
    message_type          VARCHAR(6)    NOT NULL,
    sender                VARCHAR(35)   NOT NULL,
    interchange_reference VARCHAR(14)   NOT NULL,
    message_reference     VARCHAR(14)   NOT NULL,
    document_number       VARCHAR(100),
    status                VARCHAR(10)   NOT NULL,
    error_code            VARCHAR(40),
    error_detail          VARCHAR(500),
    raw                   BYTEA         NOT NULL,
    sha256                CHAR(64)      NOT NULL,
    received_at           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_stock_edi_status CHECK (status IN ('APPLIED', 'REPLAYED', 'REJECTED'))
);
CREATE INDEX ix_stock_edi_message_company ON stock_edi_message (company_id, received_at DESC);
