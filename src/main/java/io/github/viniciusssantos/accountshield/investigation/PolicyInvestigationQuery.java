package io.github.viniciusssantos.accountshield.investigation;

import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.RoutingScopeEntry;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.simulation.PolicySegmentImpact;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Composes the policy module's own lifecycle/routing read port with rollout status and
 * side-effect-free impact analysis owned by other modules into one deterministic operator view.
 */
public interface PolicyInvestigationQuery {

    Optional<PolicyInvestigationDetail> investigate(String policyKey);

    enum ImpactAvailability {
        AVAILABLE,
        NOT_APPLICABLE,
        UNAVAILABLE
    }

    record RolloutSummary(
            String candidateVersion,
            int rolloutPercentage,
            String status,
            Instant startedAt,
            String startedBy,
            Instant updatedAt,
            Instant rolledBackAt,
            String rolledBackBy) {
    }

    record ReasonEvidence(String code, int contribution) {
    }

    record MaskedDivergentDecision(
            String maskedProtectionRequestReference,
            String redactedAccountReference,
            String originalOutcome,
            String candidateOutcome,
            int riskScore,
            List<ReasonEvidence> originalReasons) {

        public MaskedDivergentDecision {
            originalReasons = List.copyOf(Objects.requireNonNull(originalReasons, "originalReasons must not be null"));
        }
    }

    record ImpactSummary(
            String candidatePolicyVersion,
            Set<String> originalPolicyVersionsObserved,
            Set<String> algorithmVersionsObserved,
            int totalDecisions,
            int divergentDecisionsCount,
            double divergencePercentage,
            double maxDivergencePercentageThreshold,
            boolean exceedsDivergenceThreshold,
            Map<String, Map<String, Long>> transitionMatrix,
            Map<String, PolicySegmentImpact> impactByEventType,
            Map<String, PolicySegmentImpact> impactByRiskBand,
            List<MaskedDivergentDecision> divergentDecisions) {

        public ImpactSummary {
            originalPolicyVersionsObserved = Set.copyOf(
                    Objects.requireNonNull(originalPolicyVersionsObserved, "originalPolicyVersionsObserved must not be null"));
            algorithmVersionsObserved = Set.copyOf(
                    Objects.requireNonNull(algorithmVersionsObserved, "algorithmVersionsObserved must not be null"));
            transitionMatrix = Map.copyOf(Objects.requireNonNull(transitionMatrix, "transitionMatrix must not be null"));
            impactByEventType = Map.copyOf(Objects.requireNonNull(impactByEventType, "impactByEventType must not be null"));
            impactByRiskBand = Map.copyOf(Objects.requireNonNull(impactByRiskBand, "impactByRiskBand must not be null"));
            divergentDecisions = List.copyOf(Objects.requireNonNull(divergentDecisions, "divergentDecisions must not be null"));
        }
    }

    record PolicyInvestigationDetail(
            String policyKey,
            List<PolicyVersionSummary> versions,
            List<RoutingScopeEntry> routingScope,
            RolloutSummary activeRollout,
            ImpactSummary impactAnalysis,
            ImpactAvailability impactAvailability) {

        public PolicyInvestigationDetail {
            versions = List.copyOf(Objects.requireNonNull(versions, "versions must not be null"));
            routingScope = List.copyOf(Objects.requireNonNull(routingScope, "routingScope must not be null"));
            Objects.requireNonNull(impactAvailability, "impactAvailability must not be null");
            if (impactAvailability == ImpactAvailability.AVAILABLE && impactAnalysis == null) {
                throw new IllegalArgumentException("impactAnalysis is required when impactAvailability is AVAILABLE");
            }
        }
    }
}
