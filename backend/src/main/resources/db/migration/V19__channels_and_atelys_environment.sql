-- Communication channels (ADR-0015): the connection profiles of a client environment (AS2, SFTP, FTP, FTPS, HTTPS,
-- webhook), configured once and shared by Mystix (EDI flows) and Atelys (production and warehouses).
-- Secrets are never stored here: credential_ref names an entry of the secret store (keystore, vault).
CREATE TABLE comm_channel (
    id               UUID         PRIMARY KEY,
    company_id       UUID         NOT NULL REFERENCES company (id),
    name             VARCHAR(120) NOT NULL,
    protocol         VARCHAR(10)  NOT NULL,
    direction        VARCHAR(4)   NOT NULL,
    partner_id       UUID         REFERENCES partner (id),
    used_by_mystix   BOOLEAN      NOT NULL,
    used_by_atelys   BOOLEAN      NOT NULL,
    status           VARCHAR(10)  NOT NULL,
    settings         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    credential_ref   VARCHAR(120),
    created_by       VARCHAR(254) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_comm_channel_name UNIQUE (company_id, name),
    CONSTRAINT ck_comm_channel_protocol CHECK (protocol IN ('AS2', 'SFTP', 'FTP', 'FTPS', 'HTTPS', 'WEBHOOK')),
    CONSTRAINT ck_comm_channel_direction CHECK (direction IN ('IN', 'OUT', 'BOTH')),
    CONSTRAINT ck_comm_channel_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_comm_channel_usage CHECK (used_by_mystix OR used_by_atelys)
);

CREATE INDEX ix_comm_channel_company ON comm_channel (company_id, protocol, name);

-- Atelys environment of each client (ADR-0014, amended): created with the company, or at startup for companies
-- that existed before. One row per company once its base referentials exist.
CREATE TABLE atelys_environment (
    company_id     UUID        PRIMARY KEY REFERENCES company (id),
    default_site_id UUID        REFERENCES wms_site (id),
    provisioned_at TIMESTAMPTZ NOT NULL
);
