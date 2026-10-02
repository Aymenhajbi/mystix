-- Summary columns for invoice lists (portal). Nullable: invoices stored before V4 have no summary.
ALTER TABLE invoice_message ADD COLUMN buyer_name     VARCHAR(255);
ALTER TABLE invoice_message ADD COLUMN currency       CHAR(3);
ALTER TABLE invoice_message ADD COLUMN payable_amount NUMERIC(19, 4);

CREATE INDEX ix_invoice_message_company_created ON invoice_message (company_id, created_at DESC);
