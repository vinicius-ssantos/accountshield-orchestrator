ALTER TABLE policy.policy_version
    ADD COLUMN created_by VARCHAR(200),
    ADD COLUMN validated_by VARCHAR(200),
    ADD COLUMN validated_at TIMESTAMPTZ,
    ADD COLUMN approved_by VARCHAR(200),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN approval_reason VARCHAR(500);
