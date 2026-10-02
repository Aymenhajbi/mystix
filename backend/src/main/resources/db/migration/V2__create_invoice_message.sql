-- An invoice accepted by Mystix (canonical mapping, UBL XSD and EN 16931 rules passed).
-- Idempotence: one invoice number per company; a replay with the same canonical content returns the stored result.
CREATE TABLE invoice_message (
    id                UUID         PRIMARY KEY,
    company_id        UUID         NOT NULL REFERENCES company (id),
    invoice_number    VARCHAR(100) NOT NULL,
    issue_date        DATE         NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    canonical_version VARCHAR(10)  NOT NULL,
    canonical_sha256  CHAR(64)     NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_invoice_message_number UNIQUE (company_id, invoice_number),
    CONSTRAINT ck_invoice_message_status CHECK (status IN ('VALIDATED'))
);

-- Immutable processing artefacts. Stored in the database until the S3 ArtifactStore (Lot 4).
CREATE TABLE invoice_artifact (
    id          UUID         PRIMARY KEY,
    message_id  UUID         NOT NULL REFERENCES invoice_message (id),
    company_id  UUID         NOT NULL REFERENCES company (id),
    kind        VARCHAR(16)  NOT NULL,
    media_type  VARCHAR(100) NOT NULL,
    content     BYTEA        NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_invoice_artifact_kind UNIQUE (message_id, kind),
    CONSTRAINT ck_invoice_artifact_kind CHECK (kind IN ('RAW', 'CANONICAL', 'OUT'))
);

CREATE INDEX ix_invoice_artifact_company ON invoice_artifact (company_id);
