CREATE SCHEMA challenge;

CREATE TABLE challenge.challenge_plan (
    id UUID PRIMARY KEY,
    account_reference VARCHAR(128) NOT NULL,
    challenge_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    max_attempts SMALLINT NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    remaining_attempts SMALLINT NOT NULL CHECK (remaining_attempts BETWEEN 0 AND 10),
    expected_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_challenge_plan_status
        CHECK (status IN ('PENDING', 'CHALLENGED', 'VERIFIED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_challenge_plan_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_challenge_remaining CHECK (remaining_attempts <= max_attempts)
);

CREATE INDEX ix_challenge_plan_account_time
    ON challenge.challenge_plan (account_reference, created_at DESC);
