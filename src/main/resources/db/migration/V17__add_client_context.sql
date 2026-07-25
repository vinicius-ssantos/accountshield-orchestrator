ALTER TABLE protection.idempotency_record
    ADD COLUMN client_id VARCHAR(100) NOT NULL DEFAULT 'default-client';

ALTER TABLE protection.idempotency_record
    DROP CONSTRAINT uq_idempotency_key;

ALTER TABLE protection.idempotency_record
    ADD CONSTRAINT uq_idempotency_client_key UNIQUE (client_id, idempotency_key);

ALTER TABLE protection.protection_request
    ADD COLUMN client_id VARCHAR(100) NOT NULL DEFAULT 'default-client';

CREATE TABLE policy.client_policy_route (
    id UUID PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    policy_key VARCHAR(100) NOT NULL,
    CONSTRAINT uq_client_policy_route UNIQUE (client_id, event_type)
);

INSERT INTO policy.client_policy_route (id, client_id, event_type, policy_key)
VALUES
    (gen_random_uuid(), 'default-client', 'LOGIN_ATTEMPT', 'account-protection-default'),
    (gen_random_uuid(), 'default-client', 'SENSITIVE_ACTION', 'account-protection-default'),
    (gen_random_uuid(), 'default-client', 'LOGIN_RECOVERY_ATTEMPT', 'account-protection-default'),
    (gen_random_uuid(), 'default-client', 'PASSWORD_RESET_ATTEMPT', 'account-protection-default'),
    (gen_random_uuid(), 'default-client', 'CREDENTIAL_CHANGE_ATTEMPT', 'account-protection-default'),
    (gen_random_uuid(), 'default-client', 'DEVICE_TRUST_RESET_ATTEMPT', 'account-protection-default');
