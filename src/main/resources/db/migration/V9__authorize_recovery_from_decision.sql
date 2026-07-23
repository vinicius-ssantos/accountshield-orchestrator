ALTER TABLE policy.policy_version
    ADD COLUMN recovery_max_score SMALLINT;

ALTER TABLE policy.policy_version
    DROP CONSTRAINT ck_policy_score_thresholds;

ALTER TABLE policy.policy_version
    ADD CONSTRAINT ck_policy_score_thresholds
        CHECK (
            CASE
                WHEN allow_max_score IS NULL
                    AND step_up_max_score IS NULL
                    AND recovery_max_score IS NULL THEN TRUE
                WHEN jsonb_typeof(definition -> 'allowMaxScore') = 'number'
                    AND jsonb_typeof(definition -> 'stepUpMaxScore') = 'number'
                    AND recovery_max_score IS NULL
                    AND NOT (definition ? 'recoveryMaxScore')
                    THEN allow_max_score BETWEEN 0 AND 99
                        AND step_up_max_score BETWEEN 1 AND 99
                        AND allow_max_score < step_up_max_score
                        AND (definition ->> 'allowMaxScore')::SMALLINT = allow_max_score
                        AND (definition ->> 'stepUpMaxScore')::SMALLINT = step_up_max_score
                WHEN jsonb_typeof(definition -> 'allowMaxScore') = 'number'
                    AND jsonb_typeof(definition -> 'stepUpMaxScore') = 'number'
                    AND jsonb_typeof(definition -> 'recoveryMaxScore') = 'number'
                    THEN allow_max_score BETWEEN 0 AND 99
                        AND step_up_max_score BETWEEN 1 AND 99
                        AND recovery_max_score BETWEEN 0 AND 99
                        AND allow_max_score < step_up_max_score
                        AND (definition ->> 'allowMaxScore')::SMALLINT = allow_max_score
                        AND (definition ->> 'stepUpMaxScore')::SMALLINT = step_up_max_score
                        AND (definition ->> 'recoveryMaxScore')::SMALLINT = recovery_max_score
                ELSE FALSE
            END
        );

CREATE OR REPLACE FUNCTION policy.protect_activated_policy_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' THEN
        IF NEW.status = 'RETIRED'
           AND NEW.definition = OLD.definition
           AND NEW.allow_max_score IS NOT DISTINCT FROM OLD.allow_max_score
           AND NEW.step_up_max_score IS NOT DISTINCT FROM OLD.step_up_max_score
           AND NEW.recovery_max_score IS NOT DISTINCT FROM OLD.recovery_max_score
           AND NEW.version = OLD.version
           AND NEW.policy_key = OLD.policy_key THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'activated policy versions are immutable (only ACTIVE->RETIRED allowed)';
    END IF;
    RETURN NEW;
END;
$$;

UPDATE policy.policy_version
   SET status = 'RETIRED'
 WHERE policy_key = 'account-protection-default'
   AND version = '1.0.0'
   AND status = 'ACTIVE';

INSERT INTO policy.policy_version (
    id,
    policy_key,
    version,
    status,
    definition,
    allow_max_score,
    step_up_max_score,
    recovery_max_score,
    created_at,
    activated_at
) VALUES (
    '0d955faf-bf4b-4a8e-9d87-7458ceef36c1',
    'account-protection-default',
    '1.1.0',
    'ACTIVE',
    '{"allowMaxScore":29,"stepUpMaxScore":69,"recoveryMaxScore":89}'::jsonb,
    29,
    69,
    89,
    TIMESTAMPTZ '2026-07-23 00:00:00+00',
    TIMESTAMPTZ '2026-07-23 00:00:00+00'
);

ALTER TABLE recovery.recovery_flow
    ADD COLUMN originating_decision_id UUID;

UPDATE recovery.recovery_flow recovery_flow
   SET originating_decision_id = decision_trace.id
  FROM audit.decision_trace decision_trace
 WHERE recovery_flow.protection_request_id = decision_trace.protection_request_id
   AND recovery_flow.originating_decision_id IS NULL;

ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT fk_recovery_originating_decision
        FOREIGN KEY (originating_decision_id)
        REFERENCES audit.decision_trace(id);

CREATE UNIQUE INDEX uq_recovery_originating_decision
    ON recovery.recovery_flow (originating_decision_id)
    WHERE originating_decision_id IS NOT NULL;
