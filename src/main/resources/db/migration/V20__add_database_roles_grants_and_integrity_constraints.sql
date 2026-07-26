-- Database roles: separate the migration owner (this connection) from a restricted runtime
-- role and a read-only role. Passwords below are local-only placeholders, matching this
-- project's existing convention for non-production secrets (e.g. challenge/pseudonym secrets);
-- a real deployment must set these via its own secret-management mechanism at role-creation time.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accountshield_runtime') THEN
        EXECUTE 'CREATE ROLE accountshield_runtime LOGIN PASSWORD ''accountshield-local-only-runtime''';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accountshield_readonly') THEN
        EXECUTE 'CREATE ROLE accountshield_readonly LOGIN PASSWORD ''accountshield-local-only-readonly''';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA protection, policy, audit, challenge, recovery, outbox
    TO accountshield_runtime, accountshield_readonly;

-- Runtime: full DML on regular tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA protection TO accountshield_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policy TO accountshield_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA challenge TO accountshield_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA recovery TO accountshield_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA outbox TO accountshield_runtime;

-- Runtime: append-only on audit tables -- no UPDATE/DELETE, matching the existing
-- audit.reject_audit_mutation() trigger guarantee at the grant level too.
GRANT SELECT, INSERT ON audit.decision_trace, audit.decision_reason TO accountshield_runtime;

-- Future tables created by later migrations (owned by the migration role) inherit the same
-- posture automatically, without needing a manual grant added to every subsequent migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA protection
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA policy
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA challenge
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA recovery
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA outbox
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
    GRANT SELECT, INSERT ON TABLES TO accountshield_runtime;

-- Read-only role: SELECT everywhere, nothing else.
GRANT SELECT ON ALL TABLES IN SCHEMA protection TO accountshield_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA policy TO accountshield_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA audit TO accountshield_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA challenge TO accountshield_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA recovery TO accountshield_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA outbox TO accountshield_readonly;

ALTER DEFAULT PRIVILEGES IN SCHEMA protection GRANT SELECT ON TABLES TO accountshield_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA policy GRANT SELECT ON TABLES TO accountshield_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT ON TABLES TO accountshield_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA challenge GRANT SELECT ON TABLES TO accountshield_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA recovery GRANT SELECT ON TABLES TO accountshield_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA outbox GRANT SELECT ON TABLES TO accountshield_readonly;

-- Neither role owns or has any explicit grant on the immutability trigger functions
-- (audit.reject_audit_mutation, policy.protect_activated_policy_version,
-- recovery.protect_recovery_authorization) or their triggers: under Postgres's default-deny
-- model, a non-owner role with no explicit grant cannot ALTER or DROP them. Trigger firing
-- itself requires no grant to the role whose DML caused it.

-- Referential integrity: close real cross-module FK gaps found by inspection. Deliberately
-- polymorphic references (outbox.outbox_event.aggregate_id, challenge.challenge_plan.context_id)
-- are not given FKs -- they are already documented and constrained via CHECK on their
-- discriminator column (aggregate_type / purpose) instead.
ALTER TABLE audit.decision_trace
    ADD CONSTRAINT fk_decision_trace_protection_request
        FOREIGN KEY (protection_request_id) REFERENCES protection.protection_request (id);

ALTER TABLE recovery.recovery_authorization
    ADD CONSTRAINT fk_recovery_authorization_protection_request
        FOREIGN KEY (protection_request_id) REFERENCES protection.protection_request (id),
    ADD CONSTRAINT fk_recovery_authorization_decision
        FOREIGN KEY (decision_id) REFERENCES audit.decision_trace (id);

ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT fk_recovery_flow_identity_challenge
        FOREIGN KEY (identity_challenge_id) REFERENCES challenge.challenge_plan (id);

ALTER TABLE policy.policy_rollout
    ADD CONSTRAINT fk_policy_rollout_candidate_version
        FOREIGN KEY (policy_key, candidate_version) REFERENCES policy.policy_version (policy_key, version);
