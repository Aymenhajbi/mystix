-- Supply chain flows (ADR-0012, amendment 2): an exchange flow may carry the EDIFACT D96A stock messages,
-- activated and paused like invoice flows.
ALTER TABLE exchange_flow DROP CONSTRAINT ck_exchange_flow_document;
ALTER TABLE exchange_flow ADD CONSTRAINT ck_exchange_flow_document
    CHECK (document_type IN ('INVOICE', 'DESADV', 'RECADV', 'ORDERS', 'INVRPT'));

CREATE INDEX ix_exchange_flow_active_api ON exchange_flow (company_id, document_type)
    WHERE status = 'ACTIVE' AND source_channel = 'API';
