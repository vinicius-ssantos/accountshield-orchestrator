package io.github.viniciusssantos.accountshield.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionTraceCommandTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void createsValidCommandWithDefaultsApplied() {
        DecisionTraceCommand command = validCommand().build();

        assertThatThrownBy(() -> command.normalizedContext().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.reasons().add(
                new DecisionReasonContribution("X", 1, Map.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullDecisionId() {
        assertThatThrownBy(() -> validCommand().decisionId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decisionId");
    }

    @Test
    void rejectsNullProtectionRequestId() {
        assertThatThrownBy(() -> validCommand().protectionRequestId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("protectionRequestId");
    }

    @Test
    void rejectsBlankAccountReference() {
        assertThatThrownBy(() -> validCommand().accountReference("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountReference");
    }

    @Test
    void rejectsOversizedAccountReference() {
        assertThatThrownBy(() -> validCommand().accountReference("a".repeat(129)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountReference");
    }

    @Test
    void rejectsOversizedRequestFingerprint() {
        assertThatThrownBy(() -> validCommand().requestFingerprint("f".repeat(65)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint");
    }

    @Test
    void rejectsRiskScoreBelowZero() {
        assertThatThrownBy(() -> validCommand().riskScore(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskScore");
    }

    @Test
    void rejectsRiskScoreAboveHundred() {
        assertThatThrownBy(() -> validCommand().riskScore(101).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskScore");
    }

    @Test
    void acceptsRiskScoreBoundaries() {
        assertThat(validCommand().riskScore(0).build().riskScore()).isZero();
        assertThat(validCommand().riskScore(100).build().riskScore()).isEqualTo(100);
    }

    @Test
    void rejectsNullNormalizedContext() {
        assertThatThrownBy(() -> validCommand().normalizedContext(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("normalizedContext");
    }

    @Test
    void rejectsNullDecidedAt() {
        assertThatThrownBy(() -> validCommand().decidedAt(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decidedAt");
    }

    @Test
    void rejectsNullReasons() {
        assertThatThrownBy(() -> validCommand().reasons(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reasons");
    }

    @Test
    void defensivelyCopiesReasonsList() {
        List<DecisionReasonContribution> mutable = new java.util.ArrayList<>(
                List.of(new DecisionReasonContribution("CODE_A", 10, Map.of())));
        DecisionTraceCommand command = validCommand().reasons(mutable).build();

        mutable.add(new DecisionReasonContribution("CODE_B", 20, Map.of()));

        assertThat(command.reasons()).hasSize(1);
    }

    private CommandBuilder validCommand() {
        return new CommandBuilder();
    }

    private static final class CommandBuilder {
        private UUID decisionId = UUID.randomUUID();
        private UUID protectionRequestId = UUID.randomUUID();
        private String accountReference = "acct@example.com";
        private String requestFingerprint = "fp-abc123";
        private String algorithmVersion = "risk-rules-1.0";
        private String policyKey = "account-protection-default";
        private String policyVersion = "1.0.0";
        private String outcome = "ALLOW";
        private int riskScore = 15;
        private Map<String, Object> normalizedContext = Map.of("ip", "10.0.0.1");
        private Instant decidedAt = FIXED_INSTANT;
        private List<DecisionReasonContribution> reasons = List.of(
                new DecisionReasonContribution("NEW_DEVICE", 15, Map.of()));

        CommandBuilder decisionId(UUID v) { this.decisionId = v; return this; }
        CommandBuilder protectionRequestId(UUID v) { this.protectionRequestId = v; return this; }
        CommandBuilder accountReference(String v) { this.accountReference = v; return this; }
        CommandBuilder requestFingerprint(String v) { this.requestFingerprint = v; return this; }
        CommandBuilder riskScore(int v) { this.riskScore = v; return this; }
        CommandBuilder normalizedContext(Map<String, Object> v) { this.normalizedContext = v; return this; }
        CommandBuilder decidedAt(Instant v) { this.decidedAt = v; return this; }
        CommandBuilder reasons(List<DecisionReasonContribution> v) { this.reasons = v; return this; }

        DecisionTraceCommand build() {
            return new DecisionTraceCommand(
                    decisionId, protectionRequestId, accountReference, requestFingerprint,
                    algorithmVersion, policyKey, policyVersion, outcome, riskScore,
                    normalizedContext, decidedAt, reasons);
        }
    }
}
