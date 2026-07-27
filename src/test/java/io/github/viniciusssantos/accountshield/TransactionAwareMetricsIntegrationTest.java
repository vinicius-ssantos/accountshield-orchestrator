package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Proves the AFTER_COMMIT fix (ADR 0030) with the exact scenario the issue describes: a failure
 * that happens after {@code ProtectionDecisionMade} is published but before the transaction
 * actually commits (here, simulated by an extra {@code BEFORE_COMMIT}-phase listener that throws,
 * causing the real commit to fail and the transaction to roll back) must NOT have incremented the
 * success counter. Before this fix (a plain {@code @EventListener}), the counter would have
 * incremented synchronously the instant {@code decide()} published the event, regardless of the
 * later commit failure.
 */
@SpringBootTest
@Import({PostgreSqlTestConfiguration.class, TransactionAwareMetricsIntegrationTest.FailingBeforeCommitListener.class})
class TransactionAwareMetricsIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void aCommitFailureAfterPublishDoesNotIncrementTheSuccessCounter() {
        String accountReference = "tx-aware-" + UUID.randomUUID();
        double before = decisionsCounterValue();

        assertThatThrownBy(() -> protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(
                        new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                        "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                "idem-" + UUID.randomUUID())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated pre-commit failure");

        double after = decisionsCounterValue();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void decideThatActuallyCommitsDoesIncrementTheSuccessCounter() {
        // sanity check that the listener above only fires for this test's own registered
        // failing-before-commit bean's target event, and that the metric otherwise works
        FailingBeforeCommitListener.DISABLED.set(true);
        try {
            String accountReference = "tx-aware-ok-" + UUID.randomUUID();
            double before = decisionsCounterValue();

            protectionDecisionService.decide(new ProtectionDecisionCommand(
                    accountReference,
                    ProtectionEventType.LOGIN_ATTEMPT,
                    new RiskSignalEnvelope(
                            new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                            "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                    "idem-" + UUID.randomUUID()));

            double after = decisionsCounterValue();
            assertThat(after).isEqualTo(before + 1);
        } finally {
            FailingBeforeCommitListener.DISABLED.set(false);
        }
    }

    private double decisionsCounterValue() {
        return meterRegistry.find("accountshield.protection.decisions")
                .tag("outcome", "ALLOW")
                .tag("policy_key", "account-protection-default")
                .counters()
                .stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @TestConfiguration
    static class FailingBeforeCommitListener {

        static final java.util.concurrent.atomic.AtomicBoolean DISABLED = new java.util.concurrent.atomic.AtomicBoolean(false);

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        void onDecisionMade(ProtectionDecisionMade event) {
            if (!DISABLED.get()) {
                throw new RuntimeException("simulated pre-commit failure");
            }
        }
    }
}
