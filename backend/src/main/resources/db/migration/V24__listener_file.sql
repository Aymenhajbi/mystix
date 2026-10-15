-- Files acquired by polling listeners (ADR-0016): one row per content, so a file seen again (same SHA-256 on the same
-- listener) is never handed to the pipeline twice, whatever its name or the number of polls.
CREATE TABLE listener_file (
    id              UUID          PRIMARY KEY,
    company_id      UUID          NOT NULL REFERENCES company (id),
    listener_id     UUID          NOT NULL REFERENCES listener (id) ON DELETE CASCADE,
    remote_path     VARCHAR(1024) NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    sha256          CHAR(64)      NOT NULL,
    outcome         VARCHAR(10)   NOT NULL,
    correlation_id  UUID          NOT NULL,
    invoice_id      UUID,
    error_code      VARCHAR(60),
    received_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_listener_file_content UNIQUE (listener_id, sha256),
    CONSTRAINT ck_listener_file_outcome CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'REFUSED'))
);

CREATE INDEX ix_listener_file_listener ON listener_file (listener_id, received_at DESC);
