package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationContext;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabasePolicyEvaluationService implements PolicyEvaluationService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final PolicyVersionRepository policyVersionRepository;

    DatabasePolicyEvaluationService(PolicyVersionRepository policyVersionRepository) {
        this.policyVersionRepository = policyVersionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyEvaluation evaluate(
            String policyKey,
            int riskScore,
            PolicyEvaluationContext context) {
        validateInput(policyKey, riskScore, context);

        PolicyVersionEntity policy = policyVersionRepository
                .findByPolicyKeyAndStatus(policyKey, ACTIVE_STATUS)
                .orElseThrow(() -> new ActivePolicyUnavailableException(policyKey));

        return evaluatePolicy(policy, riskScore, context);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyEvaluation evaluateVersion(
            String policyKey,
            String policyVersion,
            int riskScore,
            PolicyEvaluationContext context) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (policyKey.isBlank() || policyKey.length() > 100) {
            throw new IllegalArgumentException("policyKey must contain between 1 and 100 characters");
        }
        if (policyVersion.isBlank() || policyVersion.length() > 40) {
            throw new IllegalArgumentException("policyVersion must contain between 1 and 40 characters");
        }
        validateRiskScore(riskScore);

        PolicyVersionEntity policy = policyVersionRepository
                .findByPolicyKeyAndVersion(policyKey, policyVersion)
                .orElseThrow(() -> new ActivePolicyUnavailableException(policyKey));

        return evaluatePolicy(policy, riskScore, context);
    }

    private PolicyEvaluation evaluatePolicy(
            PolicyVersionEntity policy,
            int riskScore,
            PolicyEvaluationContext context) {
        validateRiskScore(riskScore);

        ProtectionOutcome outcome;
        if (context.recoveryRequest()) {
            int recoveryMaxScore = requireRecoveryThreshold(policy);
            outcome = riskScore <= recoveryMaxScore
                    ? ProtectionOutcome.START_RECOVERY
                    : ProtectionOutcome.TEMPORARILY_BLOCK;
        } else {
            int allowMaxScore = requireThreshold(policy.getAllowMaxScore(), policy.getPolicyKey());
            int stepUpMaxScore = requireThreshold(policy.getStepUpMaxScore(), policy.getPolicyKey());
            if (allowMaxScore < 0 || allowMaxScore >= stepUpMaxScore || stepUpMaxScore >= 100) {
                throw new ActivePolicyUnavailableException(policy.getPolicyKey());
            }

            if (riskScore <= allowMaxScore) {
                outcome = ProtectionOutcome.ALLOW;
            } else if (riskScore <= stepUpMaxScore) {
                outcome = ProtectionOutcome.REQUIRE_STEP_UP;
            } else {
                outcome = ProtectionOutcome.TEMPORARILY_BLOCK;
            }
        }

        return new PolicyEvaluation(policy.getPolicyKey(), policy.getVersion(), outcome);
    }

    private void validateInput(
            String policyKey,
            int riskScore,
            PolicyEvaluationContext context) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (policyKey.isBlank() || policyKey.length() > 100) {
            throw new IllegalArgumentException("policyKey must contain between 1 and 100 characters");
        }
        validateRiskScore(riskScore);
    }

    private void validateRiskScore(int riskScore) {
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
    }

    private int requireRecoveryThreshold(PolicyVersionEntity policy) {
        int recoveryMaxScore = requireThreshold(
                policy.getRecoveryMaxScore(), policy.getPolicyKey());
        if (recoveryMaxScore < 0 || recoveryMaxScore >= 100) {
            throw new ActivePolicyUnavailableException(policy.getPolicyKey());
        }
        return recoveryMaxScore;
    }

    private int requireThreshold(Short threshold, String policyKey) {
        if (threshold == null) {
            throw new ActivePolicyUnavailableException(policyKey);
        }
        return threshold;
    }
}
