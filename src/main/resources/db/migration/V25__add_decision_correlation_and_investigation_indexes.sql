ALTER TABLE audit.decision_trace
    ADD COLUMN correlation_id VARCHAR(128);

UPDATE audit.decision_trace
   SET correlation_id = 'legacy-' || id::text
 WHERE correlation_id IS NULL;

ALTER TABLE audit.decision_trace
    ALTER COLUMN correlation_id SET NOT NULL;

CREATE INDEX ix_decision_trace_correlation_time
    ON audit.decision_trace (correlation_id, decided_at DESC, id DESC);

CREATE INDEX ix_decision_trace_queue_time
    ON audit.decision_trace (decided_at DESC, id DESC);

CREATE INDEX ix_protection_request_event_type
    ON protection.protection_request (event_type, requested_at DESC, id DESC);
