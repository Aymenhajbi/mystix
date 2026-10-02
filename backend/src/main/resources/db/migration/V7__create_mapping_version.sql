-- Executable mapping versions per flow (ADR-0008): declarative rules applied on top of the base mapping.
CREATE TABLE mapping_version (
    id            UUID         PRIMARY KEY,
    company_id    UUID         NOT NULL REFERENCES company (id),
    flow_id       UUID         NOT NULL REFERENCES exchange_flow (id),
    version       INT          NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    rules         JSONB        NOT NULL,
    rules_sha256  CHAR(64)     NOT NULL,
    test_report   JSONB,
    tested_sha256 CHAR(64),
    test_passed   BOOLEAN,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ,
    CONSTRAINT uq_mapping_version UNIQUE (flow_id, version),
    CONSTRAINT ck_mapping_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

-- At most one published version per flow.
CREATE UNIQUE INDEX uq_mapping_version_published ON mapping_version (flow_id) WHERE status = 'PUBLISHED';
CREATE INDEX ix_mapping_version_company ON mapping_version (company_id, flow_id, version DESC);
