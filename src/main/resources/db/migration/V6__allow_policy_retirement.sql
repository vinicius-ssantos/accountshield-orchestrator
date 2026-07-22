CREATE OR REPLACE FUNCTION policy.protect_activated_policy_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' THEN
        IF NEW.status = 'RETIRED'
           AND NEW.definition = OLD.definition
           AND NEW.allow_max_score = OLD.allow_max_score
           AND NEW.step_up_max_score = OLD.step_up_max_score
           AND NEW.version = OLD.version
           AND NEW.policy_key = OLD.policy_key THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'activated policy versions are immutable (only ACTIVE->RETIRED allowed)';
    END IF;
    RETURN NEW;
END;
$$;
