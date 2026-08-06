package io.github.viniciusssantos.accountshield.investigation.internal;

import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ImpactAvailability;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ImpactSummary;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.MaskedDivergentDecision;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.PolicyInvestigationDetail;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.ReasonEvidence;
import io.github.viniciusssantos.accountshield.investigation.PolicyInvestigationQuery.RolloutSummary;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery;
import io.github.viniciusssantos.accountshield.policy.PolicyDirectoryQuery.PolicyLifecycleDetail;
import io.github.viniciusssantos.accountshield.policy.PolicyRollout;
import io.github.viniciusssantos.accountshield.policy.PolicyRolloutService;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.simulation.DivergentDecision;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactAnalysisService;
import io.github.viniciusssantos.accountshield.simulation.PolicyImpactReport;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PolicyInvestigationService implements PolicyInvestigationQuery {

    private static final int MAX_IMPACT_SAMPLES = 5000;

    private final PolicyDirectoryQuery directoryQuery;
    private final PolicyRolloutService rolloutService;
    private final PolicyImpactAnalysisService impactAnalysisService;

    public PolicyInvestigationService(
            PolicyDirectoryQuery directoryQuery,
            PolicyRolloutService rolloutService,
            PolicyImpactAnalysisService impactAnalysisService) {
        this.directoryQuery = directoryQuery;
        this.rolloutService = rolloutService;
        this.impactAnalysisService = impactAnalysisService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyInvestigationDetail> investigate(String policyKey) {
        Objects.requireNonNull(policyKey, "policyKey must not be null");
        PolicyLifecycleDetail lifecycle = directoryQuery.investigate(policyKey).orElse(null);
        if (lifecycle == null) {
            return Optional.empty();
        }

        Optional<PolicyRollout> activeRollout = rolloutService.findActiveRollout(policyKey);
        RolloutSummary rolloutSummary = activeRollout.map(this::toRolloutSummary).orElse(null);

        ImpactAvailability availability;
        ImpactSummary impactSummary;
        if (activeRollout.isEmpty()) {
            availability = ImpactAvailability.NOT_APPLICABLE;
            impactSummary = null;
        } else {
            ImpactSummary computed = computeImpact(policyKey, activeRollout.get().candidateVersion());
            if (computed == null) {
                availability = ImpactAvailability.UNAVAILABLE;
                impactSummary = null;
            } else {
                availability = ImpactAvailability.AVAILABLE;
                impactSummary = computed;
            }
        }

        return Optional.of(new PolicyInvestigationDetail(
                lifecycle.policyKey(),
                lifecycle.versions(),
                lifecycle.routingScope(),
                rolloutSummary,
                impactSummary,
                availability));
    }

    private ImpactSummary computeImpact(String policyKey, String candidateVersion) {
        try {
            PolicyImpactReport report = impactAnalysisService.analyzeImpact(
                    policyKey, candidateVersion, MAX_IMPACT_SAMPLES);
            return toImpactSummary(report);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private RolloutSummary toRolloutSummary(PolicyRollout rollout) {
        return new RolloutSummary(
                rollout.candidateVersion(),
                rollout.rolloutPercentage(),
                rollout.status().name(),
                rollout.startedAt(),
                rollout.startedBy(),
                rollout.updatedAt(),
                rollout.rolledBackAt(),
                rollout.rolledBackBy());
    }

    private ImpactSummary toImpactSummary(PolicyImpactReport report) {
        return new ImpactSummary(
                report.candidatePolicyVersion(),
                report.originalPolicyVersionsObserved(),
                report.algorithmVersionsObserved(),
                report.totalDecisions(),
                report.divergentDecisionsCount(),
                report.divergencePercentage(),
                report.maxDivergencePercentageThreshold(),
                report.exceedsDivergenceThreshold(),
                report.transitionMatrix(),
                report.impactByEventType(),
                report.impactByRiskBand(),
                report.divergentDecisions().stream().map(this::toMaskedDivergentDecision).toList());
    }

    private MaskedDivergentDecision toMaskedDivergentDecision(DivergentDecision decision) {
        return new MaskedDivergentDecision(
                maskReference(decision.protectionRequestId().toString()),
                decision.redactedAccountReference(),
                decision.originalOutcome(),
                decision.candidateOutcome(),
                decision.riskScore(),
                decision.originalReasons().stream().map(this::toReasonEvidence).toList());
    }

    private ReasonEvidence toReasonEvidence(RiskReason reason) {
        return new ReasonEvidence(reason.code(), reason.contribution());
    }

    private String maskReference(String reference) {
        return "••••" + reference.substring(Math.max(0, reference.length() - 4));
    }
}
