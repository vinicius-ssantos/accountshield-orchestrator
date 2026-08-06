-- F-09 (post-v1.0.0 review): the ck_decision_outcome CHECK constraint accepted 'MONITOR', a
-- value the domain enum (policy.ProtectionOutcome) never produces. This is dead schema: the
-- database accepts a value the application cannot write, confusing anyone reading the schema
-- before the domain model. Drop and recreate the constraint without it. No existing row can
-- contain 'MONITOR' (the domain has never produced it), so this is a safe in-place alteration.
ALTER TABLE audit.decision_trace
    DROP CONSTRAINT IF EXISTS ck_decision_outcome;

ALTER TABLE audit.decision_trace
    ADD CONSTRAINT ck_decision_outcome
        CHECK (outcome IN ('ALLOW', 'REQUIRE_STEP_UP', 'TEMPORARILY_BLOCK', 'START_RECOVERY'));
