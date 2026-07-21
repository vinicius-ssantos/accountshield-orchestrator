ALTER TABLE audit.decision_reason
    ADD COLUMN ordinal SMALLINT;

ALTER TABLE audit.decision_reason
    ADD CONSTRAINT ck_decision_reason_ordinal
        CHECK (ordinal IS NULL OR ordinal >= 0);

CREATE UNIQUE INDEX uq_decision_reason_ordinal
    ON audit.decision_reason (decision_id, ordinal)
    WHERE ordinal IS NOT NULL;
