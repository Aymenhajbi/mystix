-- Atelys GPAO (ADR-0014): versioned bills of materials, work orders with their status history, material
-- consumptions, production reports, scrap and by-products. Stock effects go through the WMS ledger (V17).

CREATE TABLE gpao_bom (
    id                 UUID           PRIMARY KEY,
    company_id         UUID           NOT NULL REFERENCES company (id),
    product_id         UUID           NOT NULL REFERENCES wms_product (id),
    version            INTEGER        NOT NULL,
    -- Quantities of the lines are for this quantity of the made product, in its base unit.
    reference_quantity NUMERIC(18, 3) NOT NULL,
    status             VARCHAR(10)    NOT NULL,
    valid_from         DATE,
    valid_to           DATE,
    note               VARCHAR(500),
    created_at         TIMESTAMPTZ    NOT NULL,
    created_by         VARCHAR(254),
    validated_at       TIMESTAMPTZ,
    validated_by       VARCHAR(254),
    CONSTRAINT uq_gpao_bom_version UNIQUE (company_id, product_id, version),
    CONSTRAINT ck_gpao_bom_reference CHECK (reference_quantity > 0),
    CONSTRAINT ck_gpao_bom_status CHECK (status IN ('DRAFT', 'VALIDATED', 'OBSOLETE')),
    CONSTRAINT ck_gpao_bom_validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_gpao_bom_product ON gpao_bom (company_id, product_id, status);

CREATE TABLE gpao_bom_line (
    bom_id               UUID           NOT NULL REFERENCES gpao_bom (id),
    line_no              INTEGER        NOT NULL,
    component_id         UUID           NOT NULL REFERENCES wms_product (id),
    quantity             NUMERIC(18, 6) NOT NULL,
    -- Theoretical loss in percent, added to the requirement (5 = 5 %).
    loss_percent         NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    substitution_allowed BOOLEAN        NOT NULL DEFAULT FALSE,
    PRIMARY KEY (bom_id, line_no),
    CONSTRAINT ck_gpao_bom_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_gpao_bom_line_loss CHECK (loss_percent >= 0 AND loss_percent < 100)
);

-- Operations of the routing (optional in the MVP): sequence, line or station, standard time, instructions.
CREATE TABLE gpao_bom_operation (
    bom_id           UUID          NOT NULL REFERENCES gpao_bom (id),
    sequence         INTEGER       NOT NULL,
    name             VARCHAR(120)  NOT NULL,
    workstation      VARCHAR(80),
    standard_minutes NUMERIC(10, 2),
    instructions     VARCHAR(1000),
    quality_check    VARCHAR(500),
    PRIMARY KEY (bom_id, sequence)
);

CREATE TABLE gpao_work_order (
    id                 UUID           PRIMARY KEY,
    company_id         UUID           NOT NULL REFERENCES company (id),
    number             VARCHAR(40)    NOT NULL,
    product_id         UUID           NOT NULL REFERENCES wms_product (id),
    bom_id             UUID           NOT NULL REFERENCES gpao_bom (id),
    planned_quantity   NUMERIC(18, 3) NOT NULL,
    site_id            UUID           NOT NULL REFERENCES wms_site (id),
    production_line    VARCHAR(80),
    -- Where the finished lots go, and where materials are issued from by default.
    output_location_id UUID           NOT NULL REFERENCES wms_location (id),
    planned_start      DATE,
    planned_end        DATE,
    priority           INTEGER        NOT NULL DEFAULT 3,
    status             VARCHAR(12)    NOT NULL,
    customer_reference VARCHAR(100),
    produced_quantity  NUMERIC(18, 3) NOT NULL DEFAULT 0,
    good_quantity      NUMERIC(18, 3) NOT NULL DEFAULT 0,
    scrap_quantity     NUMERIC(18, 3) NOT NULL DEFAULT 0,
    responsible        VARCHAR(254),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_gpao_work_order_number UNIQUE (company_id, number),
    CONSTRAINT ck_gpao_work_order_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_gpao_work_order_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_gpao_work_order_dates CHECK (planned_end IS NULL OR planned_start IS NULL
                                               OR planned_end >= planned_start),
    CONSTRAINT ck_gpao_work_order_counts CHECK (produced_quantity >= 0 AND good_quantity >= 0
                                                AND scrap_quantity >= 0
                                                AND produced_quantity = good_quantity + scrap_quantity),
    CONSTRAINT ck_gpao_work_order_status CHECK (status IN ('DRAFT', 'PLANNED', 'RELEASED', 'IN_PROGRESS', 'ON_HOLD',
                                                           'COMPLETED', 'CLOSED', 'CANCELLED'))
);
CREATE INDEX ix_gpao_work_order_status ON gpao_work_order (company_id, status, planned_start);
CREATE INDEX ix_gpao_work_order_product ON gpao_work_order (company_id, product_id);

CREATE TABLE gpao_work_order_history (
    id            UUID         PRIMARY KEY,
    work_order_id UUID         NOT NULL REFERENCES gpao_work_order (id),
    from_status   VARCHAR(12),
    to_status     VARCHAR(12)  NOT NULL,
    actor         VARCHAR(254),
    reason        VARCHAR(500),
    at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_gpao_work_order_history ON gpao_work_order_history (work_order_id, at);

-- Material issued to (ISSUE) or returned from (RETURN) a work order, lot by lot; one WMS movement each.
CREATE TABLE gpao_consumption (
    id              UUID           PRIMARY KEY,
    company_id      UUID           NOT NULL REFERENCES company (id),
    work_order_id   UUID           NOT NULL REFERENCES gpao_work_order (id),
    product_id      UUID           NOT NULL REFERENCES wms_product (id),
    lot_id          UUID           NOT NULL REFERENCES wms_lot (id),
    location_id     UUID           NOT NULL REFERENCES wms_location (id),
    kind            VARCHAR(8)     NOT NULL,
    quantity        NUMERIC(18, 3) NOT NULL,
    movement_id     UUID           NOT NULL REFERENCES wms_movement (id),
    idempotency_key VARCHAR(100),
    justification   VARCHAR(500),
    actor           VARCHAR(254),
    at              TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_gpao_consumption_kind CHECK (kind IN ('ISSUE', 'RETURN')),
    CONSTRAINT ck_gpao_consumption_quantity CHECK (quantity > 0)
);
CREATE UNIQUE INDEX uq_gpao_consumption_key ON gpao_consumption (work_order_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_gpao_consumption_order ON gpao_consumption (work_order_id, at);

-- One declaration of production; report_key makes a retried declaration count once.
CREATE TABLE gpao_production_report (
    id              UUID           PRIMARY KEY,
    company_id      UUID           NOT NULL REFERENCES company (id),
    work_order_id   UUID           NOT NULL REFERENCES gpao_work_order (id),
    report_key      VARCHAR(100)   NOT NULL,
    payload_sha256  CHAR(64)       NOT NULL,
    good_quantity   NUMERIC(18, 3) NOT NULL,
    scrap_quantity  NUMERIC(18, 3) NOT NULL,
    scrap_cause     VARCHAR(200),
    production_line VARCHAR(80),
    operator        VARCHAR(120),
    comment         VARCHAR(500),
    output_lot_id   UUID           REFERENCES wms_lot (id),
    reported_at     TIMESTAMPTZ    NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL,
    actor           VARCHAR(254),
    CONSTRAINT uq_gpao_report_key UNIQUE (work_order_id, report_key),
    CONSTRAINT ck_gpao_report_quantities CHECK (good_quantity >= 0 AND scrap_quantity >= 0
                                                AND good_quantity + scrap_quantity > 0)
);
CREATE INDEX ix_gpao_report_order ON gpao_production_report (work_order_id, reported_at);
CREATE INDEX ix_gpao_report_company ON gpao_production_report (company_id, reported_at DESC);

-- Residues of a report. Exactly one fate each, so nothing is counted twice: WASTE (destroyed, no stock),
-- RECOVERED (measured and put in stock as a by-product lot) or PENDING (awaiting qualification, no stock yet).
CREATE TABLE gpao_residue (
    id            UUID           PRIMARY KEY,
    company_id    UUID           NOT NULL REFERENCES company (id),
    report_id     UUID           NOT NULL REFERENCES gpao_production_report (id),
    work_order_id UUID           NOT NULL REFERENCES gpao_work_order (id),
    product_id    UUID           REFERENCES wms_product (id),
    quantity      NUMERIC(18, 3) NOT NULL,
    unit          VARCHAR(16)    NOT NULL,
    fate          VARCHAR(10)    NOT NULL,
    -- Configured destination (pellets, compressed fuel, moulded blocks, reuse, disposal...), free code for now.
    destination   VARCHAR(40),
    lot_id        UUID           REFERENCES wms_lot (id),
    CONSTRAINT ck_gpao_residue_quantity CHECK (quantity > 0),
    CONSTRAINT ck_gpao_residue_fate CHECK (fate IN ('WASTE', 'RECOVERED', 'PENDING')),
    CONSTRAINT ck_gpao_residue_stock CHECK ((fate = 'RECOVERED') = (lot_id IS NOT NULL)),
    CONSTRAINT ck_gpao_residue_product CHECK (fate = 'WASTE' OR product_id IS NOT NULL)
);
CREATE INDEX ix_gpao_residue_order ON gpao_residue (work_order_id);
