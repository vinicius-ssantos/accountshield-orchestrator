CREATE INDEX IF NOT EXISTS idx_recovery_flow_operations_order
    ON recovery.recovery_flow (updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_recovery_flow_operations_status
    ON recovery.recovery_flow (status, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_recovery_flow_operations_classification
    ON recovery.recovery_flow (classification, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_recovery_flow_operations_event_type
    ON recovery.recovery_flow (event_type, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_recovery_flow_operations_eligibility
    ON recovery.recovery_flow (eligible_after, updated_at DESC, id DESC)
    WHERE eligible_after IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_recovery_flow_operations_risk
    ON recovery.recovery_flow (risk_score, updated_at DESC, id DESC);
