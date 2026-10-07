-- Authentication (ADR-0013): portal users, their client environments, server-side sessions.
-- Users are platform-level (a user may belong to several environments): no company_id on app_user, like vat_rate.

CREATE TABLE app_user (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    display_name    VARCHAR(120) NOT NULL,
    -- pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>; never the password.
    password_hash   VARCHAR(200) NOT NULL,
    platform_role   VARCHAR(10)  NOT NULL DEFAULT 'NONE',
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    last_login_at   TIMESTAMPTZ,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_lower CHECK (email = lower(email) AND position('@' IN email) > 1),
    CONSTRAINT ck_app_user_role CHECK (platform_role IN ('NONE', 'OPERATOR')),
    CONSTRAINT ck_app_user_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT ck_app_user_name_not_blank CHECK (length(trim(display_name)) > 0)
);

CREATE TABLE company_membership (
    user_id    UUID        NOT NULL REFERENCES app_user (id),
    company_id UUID        NOT NULL REFERENCES company (id),
    role       VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, company_id),
    CONSTRAINT ck_membership_role CHECK (role IN ('ADMIN', 'MEMBER'))
);
CREATE INDEX ix_membership_company ON company_membership (company_id);

CREATE TABLE auth_session (
    id                UUID         PRIMARY KEY,
    -- SHA-256 of the bearer token; the token itself is never stored.
    token_sha256      CHAR(64)     NOT NULL,
    user_id           UUID         NOT NULL REFERENCES app_user (id),
    active_company_id UUID         REFERENCES company (id),
    created_at        TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    user_agent        VARCHAR(200),
    CONSTRAINT uq_auth_session_token UNIQUE (token_sha256)
);
CREATE INDEX ix_auth_session_user ON auth_session (user_id, created_at DESC);
