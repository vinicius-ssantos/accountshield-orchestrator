-- Records who exported a signed evidence bundle for a decision, and why. This is a separate
-- append-only log, not an extension of audit.decision_trace's hash chain: an export is a read-only
-- act over already-recorded evidence, not a new decision-trace event, so chaining it into the same
-- sequence would conflate two different kinds of history.
CREATE TABLE audit.evidence_export_log (
    id UUID NOT NULL,
    decision_id UUID NOT NULL,
    protection_request_id UUID NOT NULL,
    exported_by VARCHAR(128) NOT NULL,
    export_reason VARCHAR(500) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_hash_algorithm VARCHAR(20) NOT NULL,
    exported_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_evidence_export_log PRIMARY KEY (id),
    CONSTRAINT fk_evidence_export_log_decision FOREIGN KEY (decision_id) REFERENCES audit.decision_trace (id)
);

CREATE INDEX ix_evidence_export_log_decision_id ON audit.evidence_export_log (decision_id);

-- Reuse the existing append-only trigger function (V1) so this is enforced at the database level,
-- not just by the accountshield_runtime grant.
CREATE TRIGGER trg_evidence_export_log_append_only
    BEFORE UPDATE OR DELETE ON audit.evidence_export_log
    FOR EACH ROW
    EXECUTE FUNCTION audit.reject_audit_mutation();

-- accountshield_runtime already gets SELECT/INSERT on new audit-schema tables via V20's
-- ALTER DEFAULT PRIVILEGES IN SCHEMA audit rule; no UPDATE/DELETE grant is added, matching
-- decision_trace's own posture, since export history must not be alterable after the fact.
