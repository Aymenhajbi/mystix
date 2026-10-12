-- Public X.509 certificates of a communication channel (ADR-0015): ours (given to the partner) and the partner's
-- (to verify its signatures and encrypt for it). Public material only: private keys stay in the secret store,
-- named by comm_channel.credential_ref. Expiry is tracked so the screens can warn before an exchange breaks.
CREATE TABLE comm_certificate (
    id                 UUID          PRIMARY KEY,
    company_id         UUID          NOT NULL REFERENCES company (id),
    channel_id         UUID          NOT NULL REFERENCES comm_channel (id) ON DELETE CASCADE,
    role               VARCHAR(8)    NOT NULL,
    subject            VARCHAR(1000) NOT NULL,
    issuer             VARCHAR(1000) NOT NULL,
    serial_number      VARCHAR(80)   NOT NULL,
    key_algorithm      VARCHAR(40)   NOT NULL,
    key_size           INT,
    not_before         TIMESTAMPTZ   NOT NULL,
    not_after          TIMESTAMPTZ   NOT NULL,
    sha256_fingerprint CHAR(95)      NOT NULL,
    pem                TEXT          NOT NULL,
    created_by         VARCHAR(254)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_comm_certificate UNIQUE (channel_id, sha256_fingerprint),
    CONSTRAINT ck_comm_certificate_role CHECK (role IN ('OWN', 'PARTNER')),
    CONSTRAINT ck_comm_certificate_validity CHECK (not_after > not_before)
);

CREATE INDEX ix_comm_certificate_company ON comm_certificate (company_id, not_after);
CREATE INDEX ix_comm_certificate_channel ON comm_certificate (channel_id);
