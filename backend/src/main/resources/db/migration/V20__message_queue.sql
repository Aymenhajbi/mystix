-- Work queue in PostgreSQL (ADR-0003, Lot 4): jobs written in the same transaction as the business message,
-- claimed with FOR UPDATE SKIP LOCKED, retried with backoff, DEAD when exhausted or permanent, replayable.
-- A lane groups the jobs of one flow (or of a company without flow): each lane has its own concurrency cap, and the
-- workers serve lanes in turn, so one client's large batch never starves the others.
CREATE TABLE queue_job (
    id            UUID         PRIMARY KEY,
    company_id    UUID         NOT NULL REFERENCES company (id),
    lane          VARCHAR(80)  NOT NULL,
    kind          VARCHAR(40)  NOT NULL,
    subject_id    UUID         NOT NULL,
    payload       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    priority      SMALLINT     NOT NULL,
    status        VARCHAR(8)   NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    max_attempts  INT          NOT NULL,
    available_at  TIMESTAMPTZ  NOT NULL,
    locked_by     VARCHAR(100),
    locked_until  TIMESTAMPTZ,
    last_error    JSONB,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ,
    -- Idempotence: one job per step of one message.
    CONSTRAINT uq_queue_job_subject UNIQUE (kind, subject_id),
    CONSTRAINT ck_queue_job_status CHECK (status IN ('READY', 'RUNNING', 'DONE', 'DEAD')),
    CONSTRAINT ck_queue_job_priority CHECK (priority BETWEEN 0 AND 9),
    CONSTRAINT ck_queue_job_attempts CHECK (attempts >= 0 AND max_attempts > 0),
    CONSTRAINT ck_queue_job_lock CHECK ((status = 'RUNNING') = (locked_until IS NOT NULL))
);

-- Claiming: ready jobs of a lane by priority then age; partial indexes stay small once jobs are DONE.
CREATE INDEX ix_queue_job_ready ON queue_job (lane, priority DESC, available_at) WHERE status = 'READY';
CREATE INDEX ix_queue_job_running ON queue_job (lane, locked_until) WHERE status = 'RUNNING';
CREATE INDEX ix_queue_job_company ON queue_job (company_id, status, created_at DESC);

-- Per-lane settings; a lane without a row uses the defaults of mystix.queue.*.
CREATE TABLE queue_lane (
    lane            VARCHAR(80) PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company (id),
    max_running     INT         NOT NULL,
    paused          BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_queue_lane_max_running CHECK (max_running > 0)
);
