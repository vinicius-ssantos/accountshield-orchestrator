ALTER TABLE policy.policy_version
    ADD COLUMN allow_max_score SMALLINT,
    ADD COLUMN step_up_max_score SMALLINT;

ALTER TABLE policy.policy_version
    ADD CONSTRAINT ck_policy_score_thresholds
        CHECK (
            CASE
                WHEN allow_max_score IS NULL AND step_up_max_score IS NULL THEN TRUE
                WHEN jsonb_typeof(definition -> 'allowMaxScore') = 'number'
                    AND jsonb_typeof(definition -> 'stepUpMaxScore') = 'number'
                    THEN allow_max_score BETWEEN 0 AND 99
                        AND step_up_max_score BETWEEN 1 AND 99
                        AND allow_max_score < step_up_max_score
                        AND (definition ->> 'allowMaxScore')::SMALLINT = allow_max_score
                        AND (definition ->> 'stepUpMaxScore')::SMALLINT = step_up_max_score
                ELSE FALSE
            END
        );

INSERT INTO policy.policy_version (
    id,
    policy_key,
    version,
    status,
    definition,
    allow_max_score,
    step_up_max_score,
    created_at,
    activated_at
) VALUES (
    'cdb2795f-e494-4c45-a89d-596aa8f04906',
    'account-protection-default',
    '1.0.0',
    'ACTIVE',
    '{"allowMaxScore":29,"stepUpMaxScore":69}'::jsonb,
    29,
    69,
    TIMESTAMPTZ '2026-07-20 00:00:00+00',
    TIMESTAMPTZ '2026-07-20 00:00:00+00'
);
