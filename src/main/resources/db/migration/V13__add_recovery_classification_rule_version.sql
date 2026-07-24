ALTER TABLE recovery.recovery_flow
    ADD COLUMN classification_rule_version VARCHAR(64) NOT NULL DEFAULT 'recovery-classification-1.0';
