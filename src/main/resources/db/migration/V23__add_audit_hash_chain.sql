-- Tamper-evident hash chaining for audit.decision_trace: each row links to the previous one by
-- content hash, so an out-of-band mutation, deletion, or reordering that bypasses
-- audit.reject_audit_mutation() (e.g. a superuser temporarily disabling the trigger, or direct
-- storage-level tampering) is detectable by recomputing hashes and comparing chain linkage --
-- something the append-only trigger alone cannot catch, since it only stops ordinary DML.
--
-- chain_sequence is application-assigned (not a database IDENTITY/SERIAL): the writer holds a
-- Postgres advisory transaction lock (pg_advisory_xact_lock) around "read last link, compute
-- next sequence and hash, insert" so concurrent decisions are serialized into one unambiguous
-- chain instead of racing on which row is "last".
ALTER TABLE audit.decision_trace
    ADD COLUMN chain_sequence BIGINT,
    ADD COLUMN previous_hash VARCHAR(64),
    ADD COLUMN record_hash VARCHAR(64),
    ADD COLUMN hash_algorithm VARCHAR(20),
    ADD COLUMN canonical_schema_version VARCHAR(40);

-- Existing rows (written before this migration) have no chain -- they are still evidence, but
-- outside the scope this feature can verify. New writes always populate every chain column, so
-- the NOT NULL/UNIQUE constraints below apply from this point forward.
ALTER TABLE audit.decision_trace
    ADD CONSTRAINT uq_decision_trace_chain_sequence UNIQUE (chain_sequence);

-- Single-row checkpoint the scheduled integrity check advances through history. Verification
-- deliberately does not advance past a detected break, so a break stays flagged on every
-- subsequent tick until an operator investigates, rather than silently marching past corrupted
-- history.
CREATE TABLE audit.chain_verification_checkpoint (
    id SMALLINT NOT NULL,
    last_verified_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ,
    CONSTRAINT pk_chain_verification_checkpoint PRIMARY KEY (id),
    CONSTRAINT chk_chain_verification_checkpoint_singleton CHECK (id = 1)
);

INSERT INTO audit.chain_verification_checkpoint (id, last_verified_sequence) VALUES (1, 0);

-- accountshield_runtime already gets SELECT/INSERT on this new table via V20's
-- ALTER DEFAULT PRIVILEGES IN SCHEMA audit rule; UPDATE is added explicitly here because
-- this table is mutable operational bookkeeping, not append-only evidence like decision_trace.
GRANT UPDATE ON audit.chain_verification_checkpoint TO accountshield_runtime;
