package io.github.viniciusssantos.accountshield.policy.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Mirrors recovery.internal.SimulatedStepUpCodeCapture (issue #195): simulated challenge
// providers (ADR 0004) never return the issued code through any endpoint, and there is otherwise
// no production-safe way for a real caller to learn it. This listener captures that plaintext for
// the policy lifecycle step-up flows (approve/activate/retire), so the operator console can
// disclose it -- clearly labeled as simulated -- instead of requiring an out-of-band channel that
// doesn't exist in this portfolio. Duplicated per module rather than shared, matching #195's own
// deliberate scope decision to generalize later only if a third call site needs it (issue #197).
// Named distinctly from the recovery module's class (not just "SimulatedStepUpCodeCapture") --
// Spring's default @Component bean naming is the unqualified simple class name regardless of
// package, so two identically-named classes in different packages collide at the whole-app
// context level even though Modulith treats them as separate modules.
@Component
class PolicySimulatedStepUpCodeCapture {

    private final Map<UUID, String> issuedCodesByChallengeId = new ConcurrentHashMap<>();

    @EventListener
    void onChallengeIssued(ChallengeIssued event) {
        issuedCodesByChallengeId.put(event.challengeId(), event.issuedCode());
    }

    String consume(UUID challengeId) {
        return issuedCodesByChallengeId.remove(challengeId);
    }
}
