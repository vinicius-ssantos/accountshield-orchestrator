-- Envelope encryption key hierarchy: a per-subject data-encryption key (DEK), wrapped by a
-- versioned key-encryption key (KEK) held only in application config, never in the database.
-- Destroying a subject's wrapped DEK (crypto-shredding) makes any value encrypted under it
-- permanently irrecoverable without deleting the rows that reference it.
CREATE SCHEMA IF NOT EXISTS crypto;

CREATE TABLE crypto.subject_key (
    subject_id VARCHAR(64) NOT NULL,
    wrapped_dek BYTEA,
    dek_nonce BYTEA,
    kek_version INT,
    created_at TIMESTAMPTZ NOT NULL,
    rewrapped_at TIMESTAMPTZ,
    destroyed_at TIMESTAMPTZ,
    CONSTRAINT pk_subject_key PRIMARY KEY (subject_id),
    -- exactly one of (live key material present) or (destroyed) holds -- never both, never neither.
    CONSTRAINT chk_subject_key_state CHECK (
        (destroyed_at IS NULL AND wrapped_dek IS NOT NULL AND dek_nonce IS NOT NULL AND kek_version IS NOT NULL)
        OR
        (destroyed_at IS NOT NULL AND wrapped_dek IS NULL AND dek_nonce IS NULL AND kek_version IS NULL)
    )
);

-- Ciphertext (subject id + nonce + AES-GCM output, base64) is wider than the plaintext account
-- reference it replaces; widen both columns this issue actually encrypts at rest.
ALTER TABLE protection.protection_request ALTER COLUMN account_reference TYPE VARCHAR(512);

GRANT USAGE ON SCHEMA crypto TO accountshield_runtime, accountshield_readonly;

-- Runtime needs to create, rewrap (UPDATE) and shred (UPDATE) subject keys, but rows are never
-- physically deleted -- crypto-shredding nulls out the key material in place instead.
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA crypto TO accountshield_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA crypto TO accountshield_readonly;

ALTER DEFAULT PRIVILEGES IN SCHEMA crypto GRANT SELECT, INSERT, UPDATE ON TABLES TO accountshield_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA crypto GRANT SELECT ON TABLES TO accountshield_readonly;
