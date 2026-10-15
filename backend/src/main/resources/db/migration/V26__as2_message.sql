-- AS2 exchanges (ADR-0004, ADR-0016): one row per message received or sent on a channel. A message received again with
-- the same Message-ID gets the same MDN back, byte for byte, and is not processed twice (RFC 4130 duplicate handling).
CREATE TABLE as2_message (
    id               UUID          PRIMARY KEY,
    company_id       UUID          NOT NULL REFERENCES company (id),
    channel_id       UUID          NOT NULL REFERENCES comm_channel (id),
    listener_id      UUID          REFERENCES listener (id) ON DELETE SET NULL,
    direction        VARCHAR(3)    NOT NULL,
    message_id       VARCHAR(998)  NOT NULL,
    as2_from         VARCHAR(128)  NOT NULL,
    as2_to           VARCHAR(128)  NOT NULL,
    payload_sha256   CHAR(64),
    payload_size     BIGINT,
    mic              VARCHAR(255),
    mdn_disposition  VARCHAR(500),
    mdn_signed       BOOLEAN,
    mic_matched      BOOLEAN,
    mdn_status       INT,
    mdn_headers      JSONB,
    mdn_body         BYTEA,
    correlation_id   UUID          NOT NULL,
    invoice_id       UUID,
    error_code       VARCHAR(60),
    duration_ms      BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_as2_message UNIQUE (channel_id, direction, message_id),
    CONSTRAINT ck_as2_message_direction CHECK (direction IN ('IN', 'OUT'))
);

CREATE INDEX ix_as2_message_company ON as2_message (company_id, created_at DESC);
