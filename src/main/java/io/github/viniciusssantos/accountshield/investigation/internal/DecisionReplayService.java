package io.github.viniciusssantos.accountshield.investigation.internal;

import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionEvidenceQuery.DecisionEvidence;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.DecisionReplayComparison;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.DecisionReplaySide;
import io.github.viniciusssantos.accountshield.investigation.DecisionReplayQuery.ReasonEvidence;
import io.github.viniciusssantos.accountshield.risk.RiskReason;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.SimulationService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DecisionReplayService implements DecisionReplayQuery {

    private final DecisionEvidenceQuery evidenceQuery;
    private final SimulationService simulationService;

    public DecisionReplayService(
            DecisionEvidenceQuery evidenceQuery,
            SimulationService simulationService) {
        this.evidenceQuery = evidenceQuery;
        this.simulationService = simulationService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DecisionReplayComparison> replay(String decisionReference) {
        DecisionEvidence evidence = evidenceQuery.findByDecisionReference(decisionReference).orElse(null);
        if (evidence == null) {
            return Optional.empty();
        }

        ReplayResult result = simulationService.replay(evidence.protectionRequestId()).orElse(null);
        if (result == null) {
            return Optional.empty();
        }

        return Optional.of(new DecisionReplayComparison(
                evidence.decision().decisionReference(),
                evidence.maskedSubjectReference(),
                result.matches(),
                new DecisionReplaySide(
                        result.originalOutcome(),
                        result.originalRiskScore(),
                        result.originalRiskBand().name(),
                        toReasons(result.originalReasons())),
                new DecisionReplaySide(
                        result.replayedOutcome(),
                        result.replayedRiskScore(),
                        result.replayedRiskBand().name(),
                        toReasons(result.replayedReasons())),
                result.policyKey(),
                result.policyVersion(),
                result.algorithmVersion(),
                result.normalizedInputSchemaVersion(),
                result.reasonCatalogVersion(),
                result.decisionEngineVersion(),
                result.mismatches()));
    }

    private List<ReasonEvidence> toReasons(List<RiskReason> reasons) {
        return reasons.stream().map(reason -> new ReasonEvidence(reason.code(), reason.contribution())).toList();
    }
}
