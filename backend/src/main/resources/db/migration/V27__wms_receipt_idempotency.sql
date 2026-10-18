-- Mobile terminals (Atelys Mobile WMS) retry a receipt creation with the same operation id: the retry must return
-- the receipt already created, never a second one. The key and the SHA-256 of the request are kept with the receipt.
ALTER TABLE wms_receipt
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN request_sha256  CHAR(64),
    ADD CONSTRAINT ck_wms_receipt_idempotency CHECK ((idempotency_key IS NULL) = (request_sha256 IS NULL));

CREATE UNIQUE INDEX uq_wms_receipt_idempotency ON wms_receipt (company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
