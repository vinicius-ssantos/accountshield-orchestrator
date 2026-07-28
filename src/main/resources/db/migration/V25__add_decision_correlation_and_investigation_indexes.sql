ALTER TABLE audit.decision_trace
    ADD COLUMN correlation_id VARCHAR(128) NOT NULL
        DEFAULT ('legacy-' || gen_random_uuid()::text);

CREATE INDEX ix_decision_trace_correlation_time
    ON audit.decision_trace (correlation_id, decided_at DESC, id DESC);

CREATE INDEX ix_decision_trace_queue_time
    ON audit.decision_trace (decided_at DESC, id DESC);

CREATE INDEX ix_decision_trace_event_time
    ON audit.decision_trace (
        (normalized_context ->> 'protectionEventType'),
        decided_at DESC,
        id DESC
    );
