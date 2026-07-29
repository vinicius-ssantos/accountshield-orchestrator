-- Issue #145 / F-18: outbox acknowledgements (markPublished/markFailedWithBackoff/
-- markDeadLettered) previously keyed only on id, so a stale claim owner (one whose claim was
-- already reclaimed as abandoned past the claim timeout) could overwrite state already written
-- by the new owner -- e.g. revert a PUBLISHED row back to PENDING. A fresh random claim_token is
-- generated on every successful claim (including a reclaim of an abandoned row); every ack now
-- requires the exact claim_token it was issued, so a stale ack affects zero rows instead of
-- silently winning the race.
ALTER TABLE outbox.outbox_event
    ADD COLUMN claim_token UUID;
