package io.github.viniciusssantos.accountshield.outbox.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeCompleted;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.policy.PrivilegedPolicyActionAttempted;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.recovery.PrivilegedRecoveryActionAttempted;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SecurityEventLogger {

    private static final Logger log = LoggerFactory.getLogger("accountshield.security");

    @EventListener
    public void onProtectionDecisionMade(ProtectionDecisionMade event) {
        log.info(
                "security_event type=PROTECTION_DECISION outcome={} risk_score={} policy={}:{} decision_id={}",
                event.outcome(),
                event.riskScore(),
                event.policyKey(),
                event.policyVersion(),
                event.decisionId());
    }

    @EventListener
    public void onChallengeCompleted(ChallengeCompleted event) {
        log.info(
                "security_event type=CHALLENGE_COMPLETED challenge_type={} final_status={} challenge_id={}",
                event.challengeType(),
                event.finalStatus(),
                event.challengeId());
    }

    @EventListener
    public void onPolicyActivated(PolicyActivated event) {
        log.info(
                "security_event type=POLICY_ACTIVATED policy={}:{}",
                event.policyKey(),
                event.version());
    }

    @EventListener
    public void onRecoveryCompleted(RecoveryCompleted event) {
        log.info(
                "security_event type=RECOVERY_COMPLETED event_type={} recovery_id={}",
                event.eventType(),
                event.recoveryId());
    }

    @EventListener
    public void onPrivilegedPolicyActionAttempted(PrivilegedPolicyActionAttempted event) {
        log.info(
                "security_event type=PRIVILEGED_ACTION_ATTEMPTED action={} policy={}:{} actor={} authorized={}",
                event.action(),
                event.policyKey(),
                event.version(),
                event.actor(),
                event.authorized());
    }

    @EventListener
    public void onPrivilegedRecoveryActionAttempted(PrivilegedRecoveryActionAttempted event) {
        log.info(
                "security_event type=PRIVILEGED_ACTION_ATTEMPTED action={} recovery_id={} actor={} authorized={}",
                event.action(),
                event.recoveryId(),
                event.actor(),
                event.authorized());
    }
}
