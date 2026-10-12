-- Atelys WMS (ADR-0014): referentials, lots, an append-only movement ledger with its balance projection,
-- reservations, receipts, transfers, inventory counts and shipments. Every table is scoped by company_id.
-- The Mystix stock module (ADR-0011) stays the stock as reported by EDI partners; Atelys is the system of record
-- of the client's own warehouses and lines.

CREATE TABLE wms_unit (
    company_id UUID        NOT NULL REFERENCES company (id),
    code       VARCHAR(16) NOT NULL,
    name       VARCHAR(80) NOT NULL,
    dimension  VARCHAR(10) NOT NULL,
    PRIMARY KEY (company_id, code),
    CONSTRAINT ck_wms_unit_dimension CHECK (dimension IN ('COUNT', 'MASS', 'VOLUME', 'LENGTH', 'AREA'))
);

CREATE TABLE wms_product (
    id               UUID         PRIMARY KEY,
    company_id       UUID         NOT NULL REFERENCES company (id),
    sku              VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(1000),
    category         VARCHAR(20)  NOT NULL,
    base_unit        VARCHAR(16)  NOT NULL,
    lot_tracked      BOOLEAN      NOT NULL,
    -- Quality status of a lot when it is received or produced: PENDING (to be released) or RELEASED.
    receipt_quality  VARCHAR(10)  NOT NULL,
    alert_threshold  NUMERIC(18, 3),
    active           BOOLEAN      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_wms_product_sku UNIQUE (company_id, sku),
    CONSTRAINT fk_wms_product_unit FOREIGN KEY (company_id, base_unit) REFERENCES wms_unit (company_id, code),
    CONSTRAINT ck_wms_product_category CHECK (category IN ('RAW_MATERIAL', 'COMPONENT', 'CONSUMABLE', 'BY_PRODUCT',
                                                           'SEMI_FINISHED', 'FINISHED')),
    CONSTRAINT ck_wms_product_quality CHECK (receipt_quality IN ('PENDING', 'RELEASED')),
    CONSTRAINT ck_wms_product_threshold CHECK (alert_threshold IS NULL OR alert_threshold >= 0)
);

-- Explicit conversions only: 1 unit = factor x base unit of the product. No implicit m3 to boards.
CREATE TABLE wms_unit_conversion (
    company_id UUID           NOT NULL REFERENCES company (id),
    product_id UUID           NOT NULL REFERENCES wms_product (id),
    unit       VARCHAR(16)    NOT NULL,
    factor     NUMERIC(18, 6) NOT NULL,
    note       VARCHAR(200),
    PRIMARY KEY (product_id, unit),
    CONSTRAINT fk_wms_conversion_unit FOREIGN KEY (company_id, unit) REFERENCES wms_unit (company_id, code),
    CONSTRAINT ck_wms_conversion_factor CHECK (factor > 0)
);

CREATE TABLE wms_site (
    id         UUID         PRIMARY KEY,
    company_id UUID         NOT NULL REFERENCES company (id),
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(120) NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_wms_site_code UNIQUE (company_id, code)
);

CREATE TABLE wms_warehouse (
    id         UUID         PRIMARY KEY,
    company_id UUID         NOT NULL REFERENCES company (id),
    site_id    UUID         NOT NULL REFERENCES wms_site (id),
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(120) NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_wms_warehouse_code UNIQUE (site_id, code)
);

-- Each site gets one TRANSIT location (warehouse_id NULL): stock shipped to that site, not yet received.
CREATE TABLE wms_location (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company (id),
    site_id      UUID        NOT NULL REFERENCES wms_site (id),
    warehouse_id UUID        REFERENCES wms_warehouse (id),
    code         VARCHAR(40) NOT NULL,
    zone         VARCHAR(40),
    type         VARCHAR(12) NOT NULL,
    active       BOOLEAN     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_wms_location_code UNIQUE (site_id, code),
    CONSTRAINT ck_wms_location_type CHECK (type IN ('RECEIVING', 'STORAGE', 'PRODUCTION', 'QUARANTINE', 'SHIPPING',
                                                    'TRANSIT')),
    CONSTRAINT ck_wms_location_transit CHECK ((type = 'TRANSIT') = (warehouse_id IS NULL))
);
CREATE INDEX ix_wms_location_warehouse ON wms_location (warehouse_id);

CREATE TABLE wms_lot (
    id             UUID         PRIMARY KEY,
    company_id     UUID         NOT NULL REFERENCES company (id),
    product_id     UUID         NOT NULL REFERENCES wms_product (id),
    lot_number     VARCHAR(64)  NOT NULL,
    origin         VARCHAR(12)  NOT NULL,
    quality_status VARCHAR(10)  NOT NULL,
    supplier       VARCHAR(200),
    supplier_lot   VARCHAR(64),
    -- Production lots: the work order that made them (gpao_work_order, no FK across modules).
    work_order_id  UUID,
    -- Opaque identifier printed in the QR code; never a secret, never personal data.
    public_code    VARCHAR(24)  NOT NULL,
    attributes     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    received_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_wms_lot_number UNIQUE (company_id, product_id, lot_number),
    CONSTRAINT uq_wms_lot_public_code UNIQUE (public_code),
    CONSTRAINT ck_wms_lot_origin CHECK (origin IN ('RECEIPT', 'PRODUCTION', 'RETURN', 'TRANSFER', 'BY_PRODUCT',
                                                   'UNTRACKED')),
    CONSTRAINT ck_wms_lot_quality CHECK (quality_status IN ('PENDING', 'RELEASED', 'BLOCKED', 'REJECTED'))
);
CREATE INDEX ix_wms_lot_product ON wms_lot (company_id, product_id);
CREATE INDEX ix_wms_lot_work_order ON wms_lot (work_order_id) WHERE work_order_id IS NOT NULL;

-- Genealogy: a child lot made from (or derived from) a parent lot, through a work order.
CREATE TABLE wms_lot_link (
    id            UUID           PRIMARY KEY,
    company_id    UUID           NOT NULL REFERENCES company (id),
    parent_lot_id UUID           NOT NULL REFERENCES wms_lot (id),
    child_lot_id  UUID           NOT NULL REFERENCES wms_lot (id),
    work_order_id UUID,
    quantity      NUMERIC(18, 3),
    created_at    TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_wms_lot_link UNIQUE (parent_lot_id, child_lot_id, work_order_id),
    CONSTRAINT ck_wms_lot_link_self CHECK (parent_lot_id <> child_lot_id)
);
CREATE INDEX ix_wms_lot_link_child ON wms_lot_link (child_lot_id);

-- Append-only ledger. Quantities in the product's base unit; the entered quantity and unit are kept for audit.
-- A confirmed movement is never updated nor deleted: a correction is a counter-movement.
CREATE TABLE wms_movement (
    id               UUID           PRIMARY KEY,
    company_id       UUID           NOT NULL REFERENCES company (id),
    type             VARCHAR(24)    NOT NULL,
    product_id       UUID           NOT NULL REFERENCES wms_product (id),
    lot_id           UUID           NOT NULL REFERENCES wms_lot (id),
    quantity         NUMERIC(18, 3) NOT NULL,
    entered_quantity NUMERIC(18, 3) NOT NULL,
    entered_unit     VARCHAR(16)    NOT NULL,
    from_location_id UUID           REFERENCES wms_location (id),
    to_location_id   UUID           REFERENCES wms_location (id),
    reference_type   VARCHAR(16)    NOT NULL,
    reference_id     UUID           NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL,
    recorded_at      TIMESTAMPTZ    NOT NULL,
    actor            VARCHAR(254),
    reason           VARCHAR(500),
    CONSTRAINT ck_wms_movement_quantity CHECK (quantity > 0 AND entered_quantity > 0),
    CONSTRAINT ck_wms_movement_locations CHECK (from_location_id IS NOT NULL OR to_location_id IS NOT NULL),
    CONSTRAINT ck_wms_movement_type CHECK (type IN ('RECEIPT', 'MOVE', 'TRANSFER_SHIP', 'TRANSFER_RECEIVE',
                                                    'PRODUCTION_ISSUE', 'PRODUCTION_RETURN', 'PRODUCTION_OUTPUT',
                                                    'BY_PRODUCT_OUTPUT', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT',
                                                    'SHIPMENT')),
    CONSTRAINT ck_wms_movement_reference CHECK (reference_type IN ('RECEIPT', 'TRANSFER', 'INVENTORY', 'WORK_ORDER',
                                                                   'SHIPMENT', 'MOVE'))
);
CREATE INDEX ix_wms_movement_lot ON wms_movement (lot_id, occurred_at);
CREATE INDEX ix_wms_movement_product ON wms_movement (company_id, product_id, occurred_at DESC);
CREATE INDEX ix_wms_movement_reference ON wms_movement (reference_type, reference_id);
CREATE INDEX ix_wms_movement_company_time ON wms_movement (company_id, occurred_at DESC);

-- Projection of the ledger per lot and location, updated in the movement's transaction. Never negative.
CREATE TABLE wms_balance (
    company_id  UUID           NOT NULL REFERENCES company (id),
    lot_id      UUID           NOT NULL REFERENCES wms_lot (id),
    location_id UUID           NOT NULL REFERENCES wms_location (id),
    product_id  UUID           NOT NULL REFERENCES wms_product (id),
    quantity    NUMERIC(18, 3) NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (lot_id, location_id),
    CONSTRAINT ck_wms_balance_quantity CHECK (quantity >= 0)
);
CREATE INDEX ix_wms_balance_product ON wms_balance (company_id, product_id) WHERE quantity > 0;
CREATE INDEX ix_wms_balance_location ON wms_balance (location_id) WHERE quantity > 0;

CREATE TABLE wms_reservation (
    id                UUID           PRIMARY KEY,
    company_id        UUID           NOT NULL REFERENCES company (id),
    product_id        UUID           NOT NULL REFERENCES wms_product (id),
    lot_id            UUID           REFERENCES wms_lot (id),
    site_id           UUID           NOT NULL REFERENCES wms_site (id),
    quantity          NUMERIC(18, 3) NOT NULL,
    consumed_quantity NUMERIC(18, 3) NOT NULL DEFAULT 0,
    purpose           VARCHAR(16)    NOT NULL,
    reference         VARCHAR(100)   NOT NULL,
    reference_id      UUID,
    status            VARCHAR(10)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    actor             VARCHAR(254),
    CONSTRAINT ck_wms_reservation_quantity CHECK (quantity > 0 AND consumed_quantity >= 0
                                                  AND consumed_quantity <= quantity),
    CONSTRAINT ck_wms_reservation_purpose CHECK (purpose IN ('WORK_ORDER', 'CUSTOMER_ORDER')),
    CONSTRAINT ck_wms_reservation_status CHECK (status IN ('ACTIVE', 'RELEASED', 'CONSUMED'))
);
CREATE INDEX ix_wms_reservation_active ON wms_reservation (company_id, product_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_wms_reservation_reference ON wms_reservation (reference_id) WHERE reference_id IS NOT NULL;

-- Document numbers per company, kind and year (REC-2026-000001...).
CREATE TABLE wms_counter (
    company_id UUID        NOT NULL REFERENCES company (id),
    kind       VARCHAR(8)  NOT NULL,
    year       INTEGER     NOT NULL,
    value      BIGINT      NOT NULL,
    PRIMARY KEY (company_id, kind, year)
);

CREATE TABLE wms_receipt (
    id              UUID         PRIMARY KEY,
    company_id      UUID         NOT NULL REFERENCES company (id),
    number          VARCHAR(40)  NOT NULL,
    site_id         UUID         NOT NULL REFERENCES wms_site (id),
    supplier        VARCHAR(200),
    source_document VARCHAR(100),
    status          VARCHAR(10)  NOT NULL,
    received_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    created_by      VARCHAR(254),
    confirmed_by    VARCHAR(254),
    CONSTRAINT uq_wms_receipt_number UNIQUE (company_id, number),
    CONSTRAINT ck_wms_receipt_status CHECK (status IN ('DRAFT', 'CONFIRMED'))
);
CREATE INDEX ix_wms_receipt_company ON wms_receipt (company_id, created_at DESC);

CREATE TABLE wms_receipt_line (
    receipt_id       UUID           NOT NULL REFERENCES wms_receipt (id),
    line_no          INTEGER        NOT NULL,
    product_id       UUID           NOT NULL REFERENCES wms_product (id),
    quantity         NUMERIC(18, 3) NOT NULL,
    unit             VARCHAR(16)    NOT NULL,
    location_id      UUID           NOT NULL REFERENCES wms_location (id),
    lot_number       VARCHAR(64),
    supplier_lot     VARCHAR(64),
    attributes       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    lot_id           UUID           REFERENCES wms_lot (id),
    PRIMARY KEY (receipt_id, line_no),
    CONSTRAINT ck_wms_receipt_line_quantity CHECK (quantity > 0)
);

-- One product and lot per transfer (MVP): DRAFT -> SHIPPED (in transit at the destination site) -> RECEIVED.
CREATE TABLE wms_transfer (
    id               UUID           PRIMARY KEY,
    company_id       UUID           NOT NULL REFERENCES company (id),
    number           VARCHAR(40)    NOT NULL,
    lot_id           UUID           NOT NULL REFERENCES wms_lot (id),
    quantity         NUMERIC(18, 3) NOT NULL,
    unit             VARCHAR(16)    NOT NULL,
    from_location_id UUID           NOT NULL REFERENCES wms_location (id),
    to_location_id   UUID           NOT NULL REFERENCES wms_location (id),
    status           VARCHAR(10)    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    shipped_at       TIMESTAMPTZ,
    received_at      TIMESTAMPTZ,
    created_by       VARCHAR(254),
    CONSTRAINT uq_wms_transfer_number UNIQUE (company_id, number),
    CONSTRAINT ck_wms_transfer_quantity CHECK (quantity > 0),
    CONSTRAINT ck_wms_transfer_locations CHECK (from_location_id <> to_location_id),
    CONSTRAINT ck_wms_transfer_status CHECK (status IN ('DRAFT', 'SHIPPED', 'RECEIVED', 'CANCELLED'))
);
CREATE INDEX ix_wms_transfer_company ON wms_transfer (company_id, created_at DESC);

CREATE TABLE wms_inventory_count (
    id           UUID         PRIMARY KEY,
    company_id   UUID         NOT NULL REFERENCES company (id),
    number       VARCHAR(40)  NOT NULL,
    warehouse_id UUID         NOT NULL REFERENCES wms_warehouse (id),
    status       VARCHAR(10)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_at  TIMESTAMPTZ,
    created_by   VARCHAR(254),
    submitted_by VARCHAR(254),
    approved_by  VARCHAR(254),
    CONSTRAINT uq_wms_inventory_number UNIQUE (company_id, number),
    CONSTRAINT ck_wms_inventory_status CHECK (status IN ('OPEN', 'SUBMITTED', 'APPROVED'))
);

-- The theoretical quantity is frozen when the line is created; the count never overwrites the stock by itself.
CREATE TABLE wms_inventory_line (
    count_id    UUID           NOT NULL REFERENCES wms_inventory_count (id),
    lot_id      UUID           NOT NULL REFERENCES wms_lot (id),
    location_id UUID           NOT NULL REFERENCES wms_location (id),
    expected    NUMERIC(18, 3) NOT NULL,
    counted     NUMERIC(18, 3),
    reason      VARCHAR(500),
    PRIMARY KEY (count_id, lot_id, location_id),
    CONSTRAINT ck_wms_inventory_counted CHECK (counted IS NULL OR counted >= 0)
);

CREATE TABLE wms_shipment (
    id           UUID         PRIMARY KEY,
    company_id   UUID         NOT NULL REFERENCES company (id),
    number       VARCHAR(40)  NOT NULL,
    site_id      UUID         NOT NULL REFERENCES wms_site (id),
    customer     VARCHAR(200),
    order_ref    VARCHAR(100),
    status       VARCHAR(10)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    shipped_at   TIMESTAMPTZ,
    created_by   VARCHAR(254),
    shipped_by   VARCHAR(254),
    CONSTRAINT uq_wms_shipment_number UNIQUE (company_id, number),
    CONSTRAINT ck_wms_shipment_status CHECK (status IN ('PICKING', 'SHIPPED'))
);

CREATE TABLE wms_shipment_line (
    shipment_id UUID           NOT NULL REFERENCES wms_shipment (id),
    line_no     INTEGER        NOT NULL,
    lot_id      UUID           NOT NULL REFERENCES wms_lot (id),
    location_id UUID           NOT NULL REFERENCES wms_location (id),
    quantity    NUMERIC(18, 3) NOT NULL,
    unit        VARCHAR(16)    NOT NULL,
    picked      NUMERIC(18, 3),
    PRIMARY KEY (shipment_id, line_no),
    CONSTRAINT ck_wms_shipment_line_quantity CHECK (quantity > 0 AND (picked IS NULL OR picked >= 0))
);

-- Audit of sensitive actions (lot release or block, BOM validation, inventory approval...).
CREATE TABLE atelys_audit (
    id          UUID         PRIMARY KEY,
    company_id  UUID         NOT NULL REFERENCES company (id),
    action      VARCHAR(40)  NOT NULL,
    object_type VARCHAR(20)  NOT NULL,
    object_id   UUID         NOT NULL,
    actor       VARCHAR(254),
    reason      VARCHAR(500),
    detail      VARCHAR(1000),
    at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_atelys_audit_object ON atelys_audit (object_type, object_id, at);
CREATE INDEX ix_atelys_audit_company ON atelys_audit (company_id, at DESC);
