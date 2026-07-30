CREATE INDEX ix_recovery_flow_queue_time
    ON recovery.recovery_flow (updated_at DESC, id DESC);

CREATE INDEX ix_recovery_flow_status_time
    ON recovery.recovery_flow (status, updated_at DESC, id DESC);

CREATE INDEX ix_recovery_flow_classification_time
    ON recovery.recovery_flow (classification, updated_at DESC, id DESC);

CREATE INDEX ix_recovery_flow_eligible_after
    ON recovery.recovery_flow (eligible_after)
 WHERE eligible_after IS NOT NULL;
