ALTER TABLE outbox.outbox_event
    ADD COLUMN IF NOT EXISTS last_error_category VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_outbox_operator_search_order
    ON outbox.outbox_event (occurred_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_operator_search_status
    ON outbox.outbox_event (status, occurred_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_operator_search_event_type
    ON outbox.outbox_event (event_type, occurred_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_recently_dead_lettered
    ON outbox.outbox_event (dead_lettered_at DESC)
    WHERE status = 'DEAD_LETTERED';

CREATE INDEX IF NOT EXISTS idx_outbox_recently_published
    ON outbox.outbox_event (published_at DESC)
    WHERE status = 'PUBLISHED';
