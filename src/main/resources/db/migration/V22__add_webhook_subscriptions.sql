-- Webhook subscriptions: each subscription's secret is stored only as ciphertext (AES-GCM,
-- app-level static key -- see WebhookSecretCipher). The plaintext secret is returned to the
-- caller exactly once, at creation and at rotation, and never again.
CREATE SCHEMA IF NOT EXISTS webhook;

CREATE TABLE webhook.webhook_subscription (
    id UUID NOT NULL,
    url VARCHAR(2048) NOT NULL,
    event_type_filter VARCHAR(160),
    secret_ciphertext BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    secret_rotated_at TIMESTAMPTZ,
    CONSTRAINT pk_webhook_subscription PRIMARY KEY (id),
    CONSTRAINT chk_webhook_subscription_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_webhook_subscription_status ON webhook.webhook_subscription (status);

GRANT USAGE ON SCHEMA webhook TO accountshield_runtime, accountshield_readonly;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA webhook TO accountshield_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA webhook TO accountshield_readonly;

ALTER DEFAULT PRIVILEGES IN SCHEMA webhook GRANT SELECT, INSERT, UPDATE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA webhook GRANT SELECT ON TABLES TO accountshield_readonly;
