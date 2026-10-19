-- Product reference (platform data, no company_id, like vat_rate): barcodes and names from the open databases
-- Open Food Facts, Open Beauty Facts and Open Products Facts (ODbL, attribution required). Used to cross-check a
-- GTIN when it is scanned or set on a product. It never creates or changes a product of a client.
CREATE TABLE product_reference_import (
    id             UUID          PRIMARY KEY,
    source         VARCHAR(10)   NOT NULL,
    file_name      VARCHAR(200)  NOT NULL,
    file_sha256    CHAR(64),
    status         VARCHAR(10)   NOT NULL,
    rows_read      BIGINT        NOT NULL DEFAULT 0,
    rows_imported  BIGINT        NOT NULL DEFAULT 0,
    rows_no_code   BIGINT        NOT NULL DEFAULT 0,
    rows_bad_check BIGINT        NOT NULL DEFAULT 0,
    error          VARCHAR(500),
    started_at     TIMESTAMPTZ   NOT NULL,
    finished_at    TIMESTAMPTZ,
    started_by     VARCHAR(254),
    CONSTRAINT ck_product_reference_import_source CHECK (source IN ('OFF', 'OBF', 'OPF')),
    CONSTRAINT ck_product_reference_import_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED'))
);

CREATE TABLE product_reference (
    gtin          CHAR(14)      NOT NULL,
    source        VARCHAR(10)   NOT NULL,
    code          VARCHAR(32)   NOT NULL,
    name          VARCHAR(300),
    brands        VARCHAR(300),
    quantity      VARCHAR(100),
    categories    VARCHAR(500),
    countries     VARCHAR(500),
    last_modified TIMESTAMPTZ,
    import_id     UUID          NOT NULL REFERENCES product_reference_import (id),
    PRIMARY KEY (gtin, source),
    CONSTRAINT ck_product_reference_gtin CHECK (gtin ~ '^[0-9]{14}$'),
    CONSTRAINT ck_product_reference_source CHECK (source IN ('OFF', 'OBF', 'OPF'))
);

-- A product of an environment may carry its GTIN (normalised to 14 digits, check digit verified by the API).
ALTER TABLE wms_product
    ADD COLUMN gtin CHAR(14),
    ADD CONSTRAINT ck_wms_product_gtin CHECK (gtin IS NULL OR gtin ~ '^[0-9]{14}$');
CREATE UNIQUE INDEX uq_wms_product_gtin ON wms_product (company_id, gtin) WHERE gtin IS NOT NULL;
