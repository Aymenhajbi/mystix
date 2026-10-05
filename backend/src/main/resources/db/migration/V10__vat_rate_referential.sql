-- Dated VAT referential (platform data, shared by every client environment: no company_id, ADR-0010).
-- A rate is in force on a date when valid_from <= date and (valid_to IS NULL OR date <= valid_to).
-- Rows are loaded by a separate, reviewed migration or by the operator API; never hard-coded in Java.
CREATE TABLE vat_rate (
    id              UUID          PRIMARY KEY,
    country_code    CHAR(2)       NOT NULL,
    category_code   VARCHAR(2)    NOT NULL,
    rate_percent    NUMERIC(5, 2) NOT NULL,
    valid_from      DATE          NOT NULL,
    valid_to        DATE,
    legal_reference VARCHAR(200)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_vat_rate_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_vat_rate_category CHECK (category_code IN ('S', 'Z', 'E', 'O')),
    CONSTRAINT ck_vat_rate_percent CHECK (rate_percent >= 0 AND rate_percent <= 100),
    CONSTRAINT ck_vat_rate_period CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_vat_rate_reference_not_blank CHECK (length(trim(legal_reference)) > 0),
    CONSTRAINT uq_vat_rate UNIQUE (country_code, category_code, rate_percent, valid_from)
);

CREATE INDEX ix_vat_rate_lookup ON vat_rate (country_code, category_code, valid_from);

COMMENT ON COLUMN vat_rate.legal_reference IS 'Source of the rate, e.g. CGI art. 99-B, LF n°, note circulaire';

-- Per-company setting (ADR-0010): a standard-rated line must carry a rate in force on the issue date.
ALTER TABLE company
    ADD COLUMN enforce_vat_rates BOOLEAN NOT NULL DEFAULT TRUE;
