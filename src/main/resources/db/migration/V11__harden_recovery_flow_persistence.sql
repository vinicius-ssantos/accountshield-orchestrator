ALTER TABLE recovery.recovery_flow
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE recovery.recovery_flow
    ALTER COLUMN protection_request_id SET NOT NULL;

ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT uq_recovery_flow_protection_request
        UNIQUE (protection_request_id);

ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT fk_recovery_flow_protection_request
        FOREIGN KEY (protection_request_id)
        REFERENCES protection.protection_request(id);

CREATE INDEX ix_recovery_flow_terminal_retention
    ON recovery.recovery_flow (updated_at)
    WHERE status IN ('COMPLETED', 'REJECTED', 'ABORTED', 'IDENTITY_FAILED');
