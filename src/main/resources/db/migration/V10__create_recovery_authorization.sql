CREATE TABLE recovery.recovery_authorization (
    id UUID PRIMARY KEY,
    protection_request_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    account_reference VARCHAR(128) NOT NULL,
    directive VARCHAR(32) NOT NULL,
    risk_score SMALLINT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT uq_recovery_authorization_protection_request
        UNIQUE (protection_request_id),
    CONSTRAINT uq_recovery_authorization_decision
        UNIQUE (decision_id),
    CONSTRAINT ck_recovery_authorization_directive
        CHECK (directive IN (
            'LOGIN',
            'PASSWORD_RESET',
            'CREDENTIAL_CHANGE',
            'DEVICE_TRUST_RESET'
        )),
    CONSTRAINT ck_recovery_authorization_risk_score
        CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_recovery_authorization_expiry
        CHECK (expires_at > issued_at),
    CONSTRAINT ck_recovery_authorization_consumption
        CHECK (
            consumed_at IS NULL
            OR (consumed_at >= issued_at AND consumed_at <= expires_at)
        )
);

CREATE INDEX ix_recovery_authorization_available
    ON recovery.recovery_authorization (expires_at)
    WHERE consumed_at IS NULL;

CREATE OR REPLACE FUNCTION recovery.protect_recovery_authorization()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.protection_request_id IS DISTINCT FROM OLD.protection_request_id
       OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
       OR NEW.account_reference IS DISTINCT FROM OLD.account_reference
       OR NEW.directive IS DISTINCT FROM OLD.directive
       OR NEW.risk_score IS DISTINCT FROM OLD.risk_score
       OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'recovery authorization fields are immutable';
    END IF;

    IF OLD.consumed_at IS NOT NULL
       AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at THEN
        RAISE EXCEPTION 'consumed recovery authorization is immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_recovery_authorization
BEFORE UPDATE ON recovery.recovery_authorization
FOR EACH ROW
EXECUTE FUNCTION recovery.protect_recovery_authorization();

INSERT INTO recovery.recovery_authorization (
    id,
    protection_request_id,
    decision_id,
    account_reference,
    directive,
    risk_score,
    issued_at,
    expires_at,
    consumed_at
)
SELECT
    recovery_flow.id,
    recovery_flow.protection_request_id,
    recovery_flow.originating_decision_id,
    recovery_flow.account_reference,
    recovery_flow.event_type,
    recovery_flow.risk_score,
    recovery_flow.initiated_at,
    recovery_flow.initiated_at + INTERVAL '100 years',
    recovery_flow.initiated_at
FROM recovery.recovery_flow recovery_flow;

ALTER TABLE recovery.recovery_flow
    ADD COLUMN authorization_id UUID;

UPDATE recovery.recovery_flow
   SET authorization_id = id;

ALTER TABLE recovery.recovery_flow
    ALTER COLUMN authorization_id SET NOT NULL;

ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT fk_recovery_flow_authorization
        FOREIGN KEY (authorization_id)
        REFERENCES recovery.recovery_authorization(id);

ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT uq_recovery_flow_authorization
        UNIQUE (authorization_id);

ALTER TABLE recovery.recovery_flow
    DROP CONSTRAINT IF EXISTS fk_recovery_originating_decision;
