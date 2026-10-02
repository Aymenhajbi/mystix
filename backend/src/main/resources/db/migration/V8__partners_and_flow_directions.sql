-- Partners of a client environment (customers, suppliers, tax authority, logistics...) and flows in both
-- directions: OUT (the client sends) and IN (the client receives). ADR-0007, amended.
CREATE TABLE partner (
    id         UUID         PRIMARY KEY,
    company_id UUID         NOT NULL REFERENCES company (id),
    name       VARCHAR(160) NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    ice        CHAR(15),
    gln        CHAR(13),
    reference  VARCHAR(60),
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_partner_name UNIQUE (company_id, name),
    CONSTRAINT ck_partner_type CHECK (type IN ('CUSTOMER', 'SUPPLIER', 'TAX_AUTHORITY', 'LOGISTICS', 'BANK', 'OTHER')),
    CONSTRAINT ck_partner_ice CHECK (ice IS NULL OR ice ~ '^[0-9]{15}$'),
    CONSTRAINT ck_partner_gln CHECK (gln IS NULL OR gln ~ '^[0-9]{13}$')
);

CREATE INDEX ix_partner_company ON partner (company_id, name);

-- A flow is tied to one partner, or to all partners when partner_id is NULL.
ALTER TABLE exchange_flow ADD COLUMN partner_id UUID REFERENCES partner (id);

-- Flows whose building blocks are not delivered yet are declared without a mapping.
ALTER TABLE exchange_flow ALTER COLUMN mapping_id DROP NOT NULL;
ALTER TABLE exchange_flow ALTER COLUMN mapping_version DROP NOT NULL;
