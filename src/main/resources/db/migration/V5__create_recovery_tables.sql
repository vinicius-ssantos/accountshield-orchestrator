CREATE SCHEMA recovery;

CREATE TABLE recovery.recovery_flow (
    id UUID PRIMARY KEY,
    account_reference VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    classification VARCHAR(24) NOT NULL,
    identity_challenge_id UUID,
    risk_score INTEGER NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    initiated_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    eligible_after TIMESTAMPTZ,
    reviewer VARCHAR(128),
    CONSTRAINT ck_recovery_flow_status
        CHECK (status IN ('INITIATED', 'VERIFYING_IDENTITY', 'IDENTITY_VERIFIED',
                          'DELAYED', 'MANUAL_REVIEW', 'COMPLETED',
                          'IDENTITY_FAILED', 'REJECTED', 'ABORTED')),
    CONSTRAINT ck_recovery_flow_classification
        CHECK (classification IN ('IMMEDIATE', 'DELAYED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_recovery_flow_event_type
        CHECK (event_type IN ('LOGIN', 'PASSWORD_RESET', 'CREDENTIAL_CHANGE', 'DEVICE_TRUST_RESET')),
    CONSTRAINT ck_recovery_updated_after_initiated CHECK (updated_at >= initiated_at)
);

CREATE INDEX ix_recovery_flow_account_time
    ON recovery.recovery_flow (account_reference, initiated_at DESC);

CREATE INDEX ix_recovery_flow_status
    ON recovery.recovery_flow (status)
    WHERE status IN ('DELAYED', 'MANUAL_REVIEW');
