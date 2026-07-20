package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.policy.ActivePolicyUnavailableException;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class DatabasePolicyEvaluationService implements PolicyEvaluationService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final PolicyVersionRepository policyVersionRepository;

    DatabasePolicyEvaluationService(PolicyVersionRepository policyVersionRepository) {
        this.policyVersionRepository = policyVersionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyEvaluation evaluate(String policyKey, int riskScore) {
        validateInput(policyKey, riskScore);

        PolicyVersionEntity policy = policyVersionRepository
                .findByPolicyKeyAndStatus(policyKey, ACTIVE_STATUS)
                .orElseThrow(() -> new ActivePolicyUnavailableException(policyKey));

        int allowMaxScore = requireThreshold(policy.getAllowMaxScore(), policyKey);
        int stepUpMaxScore = requireThreshold(policy.getStepUpMaxScore(), policyKey);
        if (allowMaxScore < 0 || allowMaxScore >= stepUpMaxScore || stepUpMaxScore >= 100) {
            throw new ActivePolicyUnavailableException(policyKey);
        }

        ProtectionOutcome outcome;
        if (riskScore <= allowMaxScore) {
            outcome = ProtectionOutcome.ALLOW;
        } else if (riskScore <= stepUpMaxScore) {
            outcome = ProtectionOutcome.REQUIRE_STEP_UP;
        } else {
            outcome = ProtectionOutcome.TEMPORARILY_BLOCK;
        }

        return new PolicyEvaluation(policy.getPolicyKey(), policy.getVersion(), outcome);
    }

    private void validateInput(String policyKey, int riskScore) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        if (policyKey.isBlank() || policyKey.length() > 100) {
            throw new IllegalArgumentException("policyKey must contain between 1 and 100 characters");
        }
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
    }

    private int requireThreshold(Integer threshold, String policyKey) {
        if (threshold == null) {
            throw new ActivePolicyUnavailableException(policyKey);
        }
        return threshold;
    }
}
