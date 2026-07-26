ALTER TABLE outbox.outbox_event
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claimed_by VARCHAR(100),
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

UPDATE outbox.outbox_event
   SET status = CASE
                    WHEN published_at IS NOT NULL THEN 'PUBLISHED'
                    WHEN attempt_count >= 5 THEN 'DEAD_LETTERED'
                    ELSE 'PENDING'
                END,
       next_attempt_at = occurred_at,
       dead_lettered_at = CASE WHEN published_at IS NULL AND attempt_count >= 5 THEN occurred_at END;

ALTER TABLE outbox.outbox_event
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ADD CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'PUBLISHED', 'DEAD_LETTERED'));

DROP INDEX outbox.ix_outbox_unpublished;

CREATE INDEX ix_outbox_claimable
    ON outbox.outbox_event (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_outbox_claimed
    ON outbox.outbox_event (claimed_at)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX ix_outbox_dead_lettered
    ON outbox.outbox_event (dead_lettered_at)
    WHERE status = 'DEAD_LETTERED';
