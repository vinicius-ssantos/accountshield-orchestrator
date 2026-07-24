ALTER TABLE challenge.challenge_plan
    ADD COLUMN code_hash VARCHAR(64);

-- fail-safe sentinel: invalidates any in-flight challenge rather than leaving it comparable
UPDATE challenge.challenge_plan
   SET code_hash = repeat('0', 64)
 WHERE code_hash IS NULL;

ALTER TABLE challenge.challenge_plan
    DROP COLUMN expected_code;
