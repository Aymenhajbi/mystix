-- Connectivity Manager (ADR-0016): entry points into the pipeline, listeners that receive on a channel
-- (comm_channel, ADR-0015) and hand messages to an entry point, and their event journal.

-- Where a message enters the Mystix pipeline: the target flow and what is expected.
CREATE TABLE entry_point (
    id             UUID         PRIMARY KEY,
    company_id     UUID         NOT NULL REFERENCES company (id),
    code           VARCHAR(60)  NOT NULL,
    name           VARCHAR(120) NOT NULL,
    flow_id        UUID         NOT NULL REFERENCES exchange_flow (id),
    document_type  VARCHAR(20)  NOT NULL,
    input_format   VARCHAR(10)  NOT NULL,
    partner_id     UUID         REFERENCES partner (id),
    enabled        BOOLEAN      NOT NULL,
    created_by     VARCHAR(254) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_entry_point_code UNIQUE (company_id, code),
    CONSTRAINT ck_entry_point_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,59}$'),
    CONSTRAINT ck_entry_point_document CHECK (document_type IN ('INVOICE')),
    CONSTRAINT ck_entry_point_format CHECK (input_format IN ('JSON', 'XML', 'EDIFACT', 'CSV'))
);

-- A runtime resource that receives on a channel. desired_state is what an administrator asked for; runtime_state is
-- what the engine observed, RUNNING only after the adapter started and passed a health check.
CREATE TABLE listener (
    id                UUID         PRIMARY KEY,
    company_id        UUID         NOT NULL REFERENCES company (id),
    name              VARCHAR(120) NOT NULL,
    kind              VARCHAR(20)  NOT NULL,
    channel_id        UUID         NOT NULL REFERENCES comm_channel (id),
    entry_point_id    UUID         NOT NULL REFERENCES entry_point (id),
    settings          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- SHA-256 of the API key (hex); the key itself is shown once and never stored.
    api_key_hash      CHAR(64),
    api_key_hint      VARCHAR(8),
    enabled           BOOLEAN      NOT NULL,
    desired_state     VARCHAR(8)   NOT NULL,
    runtime_state     VARCHAR(10)  NOT NULL,
    runtime_instance  VARCHAR(100),
    last_heartbeat_at TIMESTAMPTZ,
    last_error        JSONB,
    started_at        TIMESTAMPTZ,
    stopped_at        TIMESTAMPTZ,
    last_activity_at  TIMESTAMPTZ,
    received_count    BIGINT       NOT NULL DEFAULT 0,
    rejected_count    BIGINT       NOT NULL DEFAULT 0,
    created_by        VARCHAR(254) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_listener_name UNIQUE (company_id, name),
    CONSTRAINT ck_listener_kind CHECK (kind IN ('HTTP_INBOUND', 'SFTP_POLLING', 'AS2_SERVER')),
    CONSTRAINT ck_listener_desired CHECK (desired_state IN ('RUNNING', 'STOPPED')),
    CONSTRAINT ck_listener_runtime CHECK (runtime_state IN ('CONFIGURED', 'STARTING', 'RUNNING', 'DEGRADED',
                                                            'STOPPING', 'STOPPED', 'ERROR', 'DISABLED'))
);

CREATE INDEX ix_listener_company ON listener (company_id, name);
CREATE INDEX ix_listener_desired ON listener (desired_state) WHERE desired_state = 'RUNNING';

-- What happened to a listener: lifecycle, received and refused messages. No secret and no message content.
CREATE TABLE listener_event (
    id              UUID         PRIMARY KEY,
    company_id      UUID         NOT NULL REFERENCES company (id),
    listener_id     UUID         NOT NULL REFERENCES listener (id) ON DELETE CASCADE,
    event_type      VARCHAR(30)  NOT NULL,
    severity        VARCHAR(5)   NOT NULL,
    correlation_id  UUID,
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    actor           VARCHAR(254),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_listener_event_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX ix_listener_event_listener ON listener_event (listener_id, created_at DESC);
CREATE INDEX ix_listener_event_correlation ON listener_event (correlation_id) WHERE correlation_id IS NOT NULL;
