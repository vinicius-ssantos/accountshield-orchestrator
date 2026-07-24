ALTER TABLE recovery.recovery_flow
    ADD CONSTRAINT ck_recovery_flow_delayed_has_eligibility
        CHECK (classification <> 'DELAYED' OR eligible_after IS NOT NULL);
