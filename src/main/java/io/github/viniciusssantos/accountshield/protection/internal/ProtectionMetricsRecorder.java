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
    }
}
