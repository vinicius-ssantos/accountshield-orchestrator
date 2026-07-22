package io.github.viniciusssantos.accountshield.simulation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.viniciusssantos.accountshield.audit.DecisionTraceQuery;
import io.github.viniciusssantos.accountshield.audit.DecisionTraceView;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluation;
import io.github.viniciusssantos.accountshield.policy.PolicyEvaluationService;
import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.simulation.ReplayResult;
import io.github.viniciusssantos.accountshield.simulation.ShadowEvaluationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulationApplicationServiceTest {

    private final DecisionTraceQuery decisionTraceQuery = mock(DecisionTraceQuery.class);
    private final PolicyEvaluationService policyEvaluationService = mock(PolicyEvaluationService.class);
    private SimulationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SimulationApplicationService(decisionTraceQuery, policyEvaluationService);
    }

    @Test
    void replayReturnsMatchingWhenOutcomeIsIdentical() {
        UUID requestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(requestId))
                .thenReturn(Optional.of(trace(requestId, "ALLOW", 15, "1.0.0")));
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 15))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isTrue();
        assertThat(result.get().originalOutcome()).isEqualTo("ALLOW");
        assertThat(result.get().replayedOutcome()).isEqualTo("ALLOW");
    }

    @Test
    void replayReturnsMismatchWhenOutcomeChanged() {
        UUID requestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(requestId))
                .thenReturn(Optional.of(trace(requestId, "ALLOW", 20, "1.0.0")));
        when(policyEvaluationService.evaluateVersion("account-protection-default", "1.0.0", 20))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.REQUIRE_STEP_UP));

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isPresent();
        assertThat(result.get().matches()).isFalse();
        assertThat(result.get().originalOutcome()).isEqualTo("ALLOW");
        assertThat(result.get().replayedOutcome()).isEqualTo("REQUIRE_STEP_UP");
    }

    @Test
    void replayReturnsEmptyWhenTraceNotFound() {
        UUID requestId = UUID.randomUUID();
        when(decisionTraceQuery.findByProtectionRequestId(requestId))
                .thenReturn(Optional.empty());

        Optional<ReplayResult> result = service.replay(requestId);

        assertThat(result).isEmpty();
    }

    @Test
    void shadowEvaluationReturnsDivergedWhenOutcomesDiffer() {
        when(policyEvaluationService.evaluate("account-protection-default", 50))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.REQUIRE_STEP_UP));
        when(policyEvaluationService.evaluateVersion("account-protection-default", "2.0.0", 50))
                .thenReturn(new PolicyEvaluation("account-protection-default", "2.0.0", ProtectionOutcome.TEMPORARILY_BLOCK));

        ShadowEvaluationResult result = service.evaluateShadow(
                "account-protection-default", 50, "2.0.0");

        assertThat(result.diverged()).isTrue();
        assertThat(result.liveOutcome()).isEqualTo("REQUIRE_STEP_UP");
        assertThat(result.shadowOutcome()).isEqualTo("TEMPORARILY_BLOCK");
        assertThat(result.shadowPolicyVersion()).isEqualTo("2.0.0");
    }

    @Test
    void shadowEvaluationReturnsConvergedWhenOutcomesMatch() {
        when(policyEvaluationService.evaluate(anyString(), anyInt()))
                .thenReturn(new PolicyEvaluation("account-protection-default", "1.0.0", ProtectionOutcome.ALLOW));
        when(policyEvaluationService.evaluateVersion(anyString(), eq("2.0.0"), anyInt()))
                .thenReturn(new PolicyEvaluation("account-protection-default", "2.0.0", ProtectionOutcome.ALLOW));

        ShadowEvaluationResult result = service.evaluateShadow(
                "account-protection-default", 10, "2.0.0");

        assertThat(result.diverged()).isFalse();
    }

    private DecisionTraceView trace(UUID requestId, String outcome, int riskScore, String policyVersion) {
        return new DecisionTraceView(
                UUID.randomUUID(), requestId, "user-ref", "fingerprint",
                "risk-rules-1.0", "account-protection-default", policyVersion,
                outcome, riskScore,
                Map.of("failedAttempts", 0, "newDevice", false,
                       "impossibleTravel", false, "compromisedCredential", false,
                       "networkRiskLevel", "LOW"),
                Instant.parse("2026-07-21T12:00:00Z"),
                List.of());
    }
}
