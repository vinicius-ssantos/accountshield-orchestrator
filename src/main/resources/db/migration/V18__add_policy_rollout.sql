CREATE TABLE policy.policy_rollout (
    id UUID PRIMARY KEY,
    policy_key VARCHAR(100) NOT NULL,
    candidate_version VARCHAR(40) NOT NULL,
    rollout_percentage SMALLINT NOT NULL CHECK (rollout_percentage BETWEEN 0 AND 100),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'ROLLED_BACK')),
    started_at TIMESTAMPTZ NOT NULL,
    started_by VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    rolled_back_at TIMESTAMPTZ,
    rolled_back_by VARCHAR(200)
);

CREATE UNIQUE INDEX ux_policy_rollout_active_per_key
    ON policy.policy_rollout (policy_key)
    WHERE status = 'ACTIVE';
