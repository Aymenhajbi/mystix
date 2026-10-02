-- Exchange flows: each client environment (company) has its own flows. A flow ties a document type, a source
-- channel and format, a target format and channel, and the mapping used between them.
CREATE TABLE exchange_flow (
    id              UUID         PRIMARY KEY,
    company_id      UUID         NOT NULL REFERENCES company (id),
    name            VARCHAR(120) NOT NULL,
    document_type   VARCHAR(20)  NOT NULL,
    direction       VARCHAR(3)   NOT NULL,
    source_channel  VARCHAR(20)  NOT NULL,
    source_format   VARCHAR(40)  NOT NULL,
    target_format   VARCHAR(40)  NOT NULL,
    target_channel  VARCHAR(20)  NOT NULL,
    mapping_id      VARCHAR(60)  NOT NULL,
    mapping_version VARCHAR(10)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_exchange_flow_name UNIQUE (company_id, name),
    CONSTRAINT ck_exchange_flow_document CHECK (document_type IN ('INVOICE')),
    CONSTRAINT ck_exchange_flow_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_exchange_flow_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED'))
);

CREATE INDEX ix_exchange_flow_company ON exchange_flow (company_id, created_at);

ALTER TABLE invoice_message ADD COLUMN flow_id UUID REFERENCES exchange_flow (id);
CREATE INDEX ix_invoice_message_flow ON invoice_message (company_id, flow_id, created_at DESC);

-- Every existing company gets the flow that matches what Mystix already does for it, and its invoices join it.
INSERT INTO exchange_flow (id, company_id, name, document_type, direction, source_channel, source_format,
                           target_format, target_channel, mapping_id, mapping_version, status, created_at,
                           updated_at)
SELECT gen_random_uuid(), id, 'Factures API vers UBL 2.1', 'INVOICE', 'OUT', 'API', 'JSON_CANONICAL', 'UBL_2_1',
       'API_RESPONSE', 'ubl-invoice', '1.0', 'ACTIVE', now(), now()
FROM company;

UPDATE invoice_message m
SET flow_id = f.id
FROM exchange_flow f
WHERE f.company_id = m.company_id;
