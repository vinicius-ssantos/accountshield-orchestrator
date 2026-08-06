ALTER TABLE policy.policy_version
    ADD COLUMN created_by VARCHAR(200),
    ADD COLUMN validated_by VARCHAR(200),
    ADD COLUMN validated_at TIMESTAMPTZ,
    ADD COLUMN approved_by VARCHAR(200),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN approval_reason VARCHAR(500);

ALTER TABLE policy.policy_version
    DROP CONSTRAINT ck_policy_version_status;

ALTER TABLE policy.policy_version
    ADD CONSTRAINT ck_policy_version_status
        CHECK (status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'ACTIVE', 'RETIRED', 'REJECTED'));
