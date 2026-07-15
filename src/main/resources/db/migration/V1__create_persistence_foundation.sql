CREATE SCHEMA protection;
CREATE SCHEMA policy;
CREATE SCHEMA audit;
CREATE SCHEMA outbox;

CREATE TABLE protection.protection_request (
    id UUID PRIMARY KEY,
    account_reference VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_protection_request_status
        CHECK (status IN ('RECEIVED', 'DECIDED', 'REJECTED'))
);

CREATE INDEX ix_protection_request_account_time
    ON protection.protection_request (account_reference, requested_at DESC);

CREATE TABLE protection.idempotency_record (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    response_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_expiry
    ON protection.idempotency_record (expires_at);

CREATE TABLE policy.policy_version (
    id UUID PRIMARY KEY,
    policy_key VARCHAR(100) NOT NULL,
    version VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    definition JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_policy_version UNIQUE (policy_key, version),
    CONSTRAINT ck_policy_version_status
        CHECK (status IN ('DRAFT', 'VALIDATED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT ck_active_policy_activation
        CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_single_active_policy
    ON policy.policy_version (policy_key)
    WHERE status = 'ACTIVE';

CREATE OR REPLACE FUNCTION policy.protect_activated_policy_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'activated policy versions are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_policy_version_immutable
    BEFORE UPDATE OR DELETE ON policy.policy_version
    FOR EACH ROW
    EXECUTE FUNCTION policy.protect_activated_policy_version();

CREATE TABLE audit.decision_trace (
    id UUID PRIMARY KEY,
    protection_request_id UUID NOT NULL,
    account_reference VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(40) NOT NULL,
    policy_key VARCHAR(100) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    risk_score SMALLINT NOT NULL,
    normalized_context JSONB NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_decision_request UNIQUE (protection_request_id),
    CONSTRAINT ck_decision_outcome
        CHECK (outcome IN ('ALLOW', 'MONITOR', 'REQUIRE_STEP_UP', 'TEMPORARILY_BLOCK', 'START_RECOVERY')),
    CONSTRAINT ck_risk_score CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE INDEX ix_decision_trace_account_time
    ON audit.decision_trace (account_reference, decided_at DESC);

CREATE TABLE audit.decision_reason (
    id UUID PRIMARY KEY,
    decision_id UUID NOT NULL REFERENCES audit.decision_trace(id),
    code VARCHAR(64) NOT NULL,
    contribution SMALLINT NOT NULL,
    details JSONB,
    CONSTRAINT uq_decision_reason UNIQUE (decision_id, code),
    CONSTRAINT ck_reason_contribution CHECK (contribution BETWEEN -100 AND 100)
);

CREATE OR REPLACE FUNCTION audit.reject_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit records are append-only';
END;
$$;

CREATE TRIGGER trg_decision_trace_append_only
    BEFORE UPDATE OR DELETE ON audit.decision_trace
    FOR EACH ROW
    EXECUTE FUNCTION audit.reject_audit_mutation();

CREATE TRIGGER trg_decision_reason_append_only
    BEFORE UPDATE OR DELETE ON audit.decision_reason
    FOR EACH ROW
    EXECUTE FUNCTION audit.reject_audit_mutation();

CREATE TABLE outbox.outbox_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_unpublished
    ON outbox.outbox_event (occurred_at)
    WHERE published_at IS NULL;
