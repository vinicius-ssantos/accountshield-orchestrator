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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Logs security events only after the originating transaction commits (see
 * {@code ProtectionMetricsRecorder}'s javadoc for why a plain {@code @EventListener} would be
 * wrong here): a rolled-back decision, challenge, policy activation, or recovery must not appear
 * in the security log as if it had actually happened.
 */
@Component
public class SecurityEventLogger {

    private static final Logger log = LoggerFactory.getLogger("accountshield.security");

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProtectionDecisionMade(ProtectionDecisionMade event) {
        log.info(
                "security_event type=PROTECTION_DECISION outcome={} risk_score={} policy={}:{} decision_id={}"
                        + " degraded={} degradation_reason={}",
                event.outcome(),
                event.riskScore(),
                event.policyKey(),
                event.policyVersion(),
                event.decisionId(),
                event.degraded(),
                event.degradationReason());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChallengeCompleted(ChallengeCompleted event) {
        log.info(
                "security_event type=CHALLENGE_COMPLETED challenge_type={} final_status={} challenge_id={}",
                event.challengeType(),
                event.finalStatus(),
                event.challengeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPolicyActivated(PolicyActivated event) {
        log.info(
                "security_event type=POLICY_ACTIVATED policy={}:{}",
                event.policyKey(),
                event.version());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecoveryCompleted(RecoveryCompleted event) {
        log.info(
                "security_event type=RECOVERY_COMPLETED event_type={} recovery_id={}",
                event.eventType(),
                event.recoveryId());
    }

    // Deliberately a plain @EventListener, not AFTER_COMMIT: DatabasePolicyRolloutService.consumeStepUp
    // publishes this with authorized=false and then rethrows, rolling back the transaction --
    // an AFTER_COMMIT listener would never see a denied attempt at all. An "attempt" audit trail
    // must record both outcomes, unlike the success-only metrics/logs above.
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

    // Same reasoning as onPrivilegedPolicyActionAttempted above: RecoveryApplicationService
    // .consumeReviewStepUp publishes this with authorized=false and then rethrows.
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
