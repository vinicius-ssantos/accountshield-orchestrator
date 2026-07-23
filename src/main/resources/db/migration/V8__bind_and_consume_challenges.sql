ALTER TABLE challenge.challenge_plan
    ADD COLUMN purpose VARCHAR(32),
    ADD COLUMN context_id UUID,
    ADD COLUMN consumed_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Historical demo rows predate explicit binding. Bind them to their own IDs so
-- they cannot accidentally authorize a current operation after this migration.
UPDATE challenge.challenge_plan
SET purpose = 'PROTECTION_STEP_UP',
    context_id = id
WHERE purpose IS NULL OR context_id IS NULL;

ALTER TABLE challenge.challenge_plan
    ALTER COLUMN purpose SET NOT NULL,
    ALTER COLUMN context_id SET NOT NULL;

ALTER TABLE challenge.challenge_plan
    DROP CONSTRAINT ck_challenge_plan_status;

ALTER TABLE challenge.challenge_plan
    ADD CONSTRAINT ck_challenge_plan_status
        CHECK (status IN ('PENDING', 'CHALLENGED', 'VERIFIED', 'CONSUMED', 'FAILED', 'EXPIRED')),
    ADD CONSTRAINT ck_challenge_plan_purpose
        CHECK (purpose IN ('PROTECTION_STEP_UP', 'RECOVERY_IDENTITY', 'PRIVILEGED_OPERATION')),
    ADD CONSTRAINT ck_challenge_consumed_at
        CHECK ((status = 'CONSUMED' AND consumed_at IS NOT NULL)
            OR (status <> 'CONSUMED' AND consumed_at IS NULL));

CREATE INDEX ix_challenge_plan_purpose_context
    ON challenge.challenge_plan (purpose, context_id);
