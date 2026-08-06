package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records success metrics only after the originating transaction actually commits. A plain
 * {@code @EventListener} would fire the instant {@code eventPublisher.publishEvent(...)} is
 * called, regardless of whether the surrounding transaction later rolls back (e.g. a failure
 * during commit itself, or an outer caller's transaction rolling back the whole unit of work
 * afterward) -- incorrectly recording success for a decision that never actually persisted.
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} defers this listener's body until
 * Spring's transaction synchronization confirms a real commit; on rollback, it simply never runs.
 */
@Component
public class ProtectionMetricsRecorder {

    private static final String DECISIONS_METRIC = "accountshield.protection.decisions";
    private static final String RISK_SCORE_METRIC = "accountshield.protection.risk_score";
    private static final String DEGRADED_DECISIONS_METRIC = "accountshield.protection.degraded_decisions";
    private static final String ROLLOUT_DECISIONS_METRIC = "accountshield.policy.rollout.decisions";

    private final MeterRegistry meterRegistry;

    public ProtectionMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDecisionMade(ProtectionDecisionMade event) {
        Counter.builder(DECISIONS_METRIC)
                .description("Total protection decisions made")
                .tag("outcome", event.outcome())
                .tag("policy_key", event.policyKey())
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder(RISK_SCORE_METRIC)
                .description("Distribution of risk scores in protection decisions")
                .tag("outcome", event.outcome())
                .register(meterRegistry)
                .record(event.riskScore());

        if (event.degraded()) {
            Counter.builder(DEGRADED_DECISIONS_METRIC)
                    .description("Total decisions produced under a dependency-failure degradation strategy")
                    .tag("reason", event.degradationReason())
                    .register(meterRegistry)
                    .increment();
        }

        if (event.rolloutCandidateSelected() != null) {
            Counter.builder(ROLLOUT_DECISIONS_METRIC)
                    .description("Total decisions made while a policy rollout was active, by cohort selection")
                    .tag("policy_key", event.policyKey())
                    .tag("selection", event.rolloutCandidateSelected() ? "candidate" : "stable")
                    .register(meterRegistry)
                    .increment();
        }
    }
}
