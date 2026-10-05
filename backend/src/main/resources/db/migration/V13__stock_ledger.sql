-- Stock module (ADR-0011): business events, append-only movement ledger, positions per state, variances, alerts.

CREATE TABLE stock_event (
    id               UUID         PRIMARY KEY,
    company_id       UUID         NOT NULL REFERENCES company (id),
    type             VARCHAR(30)  NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    payload_sha256   CHAR(64)     NOT NULL,
    document_number  VARCHAR(100),
    reference_number VARCHAR(100),
    occurred_at      TIMESTAMPTZ  NOT NULL,
    recorded_at      TIMESTAMPTZ  NOT NULL,
    -- SNAPSHOT only: lines compared and lines without variance (inventory accuracy rate).
    lines_compared   INTEGER,
    lines_matched    INTEGER,
    CONSTRAINT uq_stock_event_key UNIQUE (company_id, idempotency_key),
    CONSTRAINT ck_stock_event_type CHECK (type IN ('SHIPMENT_NOTICE_IN', 'RECEIPT', 'ORDER', 'SHIPMENT_OUT',
                                                   'STATUS_CHANGE', 'ADJUSTMENT', 'SNAPSHOT'))
);
CREATE INDEX ix_stock_event_document ON stock_event (company_id, type, document_number);

CREATE TABLE stock_movement (
    id          UUID           PRIMARY KEY,
    company_id  UUID           NOT NULL REFERENCES company (id),
    event_id    UUID           NOT NULL REFERENCES stock_event (id),
    sku         VARCHAR(64)    NOT NULL,
    location    VARCHAR(64)    NOT NULL,
    from_state  VARCHAR(20),
    to_state    VARCHAR(20),
    quantity    NUMERIC(18, 3) NOT NULL,
    occurred_at TIMESTAMPTZ    NOT NULL,
    recorded_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_movement_states CHECK (from_state IS NOT NULL OR to_state IS NOT NULL),
    CONSTRAINT ck_stock_movement_from CHECK (from_state IN ('AVAILABLE', 'RESERVED', 'IN_TRANSIT', 'QUARANTINE', 'CONSIGNMENT')),
    CONSTRAINT ck_stock_movement_to CHECK (to_state IN ('AVAILABLE', 'RESERVED', 'IN_TRANSIT', 'QUARANTINE', 'CONSIGNMENT'))
);
CREATE INDEX ix_stock_movement_item ON stock_movement (company_id, sku, location, occurred_at);
CREATE INDEX ix_stock_movement_event ON stock_movement (company_id, event_id);

-- Projection of the ledger, updated in the same transaction as the movements.
CREATE TABLE stock_position (
    company_id UUID           NOT NULL REFERENCES company (id),
    sku        VARCHAR(64)    NOT NULL,
    location   VARCHAR(64)    NOT NULL,
    state      VARCHAR(20)    NOT NULL,
    quantity   NUMERIC(18, 3) NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (company_id, sku, location, state),
    CONSTRAINT ck_stock_position_state CHECK (state IN ('AVAILABLE', 'RESERVED', 'IN_TRANSIT', 'QUARANTINE', 'CONSIGNMENT'))
);

-- Snapshot reconciliation results (one row per compared line).
CREATE TABLE stock_variance (
    id         UUID           PRIMARY KEY,
    company_id UUID           NOT NULL REFERENCES company (id),
    event_id   UUID           NOT NULL REFERENCES stock_event (id),
    sku        VARCHAR(64)    NOT NULL,
    location   VARCHAR(64)    NOT NULL,
    state      VARCHAR(20)    NOT NULL,
    reported   NUMERIC(18, 3) NOT NULL,
    expected   NUMERIC(18, 3) NOT NULL,
    delta      NUMERIC(18, 3) NOT NULL,
    as_of      TIMESTAMPTZ    NOT NULL,
    recorded_at TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_stock_variance_company ON stock_variance (company_id, recorded_at DESC);

-- Business alerts (supplier dispute on a receipt, inventory variance).
CREATE TABLE stock_alert (
    id          UUID           PRIMARY KEY,
    company_id  UUID           NOT NULL REFERENCES company (id),
    event_id    UUID           NOT NULL REFERENCES stock_event (id),
    kind        VARCHAR(30)    NOT NULL,
    sku         VARCHAR(64)    NOT NULL,
    location    VARCHAR(64)    NOT NULL,
    expected    NUMERIC(18, 3),
    actual      NUMERIC(18, 3),
    message     VARCHAR(500)   NOT NULL,
    recorded_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_stock_alert_kind CHECK (kind IN ('RECEIPT_DISCREPANCY', 'INVENTORY_VARIANCE'))
);
CREATE INDEX ix_stock_alert_company ON stock_alert (company_id, recorded_at DESC);
