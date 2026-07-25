package io.github.viniciusssantos.accountshield.protection.internal;

import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ProtectionMetricsRecorder {

    private static final String DECISIONS_METRIC = "accountshield.protection.decisions";
    private static final String RISK_SCORE_METRIC = "accountshield.protection.risk_score";
    private static final String DEGRADED_DECISIONS_METRIC = "accountshield.protection.degraded_decisions";

    private final MeterRegistry meterRegistry;

    public ProtectionMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener
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
    }
}
