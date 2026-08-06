package io.github.viniciusssantos.accountshield.recovery.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Simulated challenge providers (ADR 0004) never return the issued code through any endpoint --
// codes are hashed at rest immediately (HmacChallengeCodeHasher) and ChallengeIssued carries the
// plaintext only transiently, the same way integration tests observe it via
// @RecordApplicationEvents. There is otherwise no production-safe way for a real caller to learn
// it. This listener captures that plaintext for the recovery-review step-up flow only, so the
// operator console can disclose it -- clearly labeled as simulated -- instead of requiring an
// out-of-band channel that doesn't exist in this portfolio. Protection step-up and recovery
// identity confirmation have the same underlying limitation; narrowing this to recovery review is
// a deliberate scope decision, not an oversight (see issue #194).
@Component
class SimulatedStepUpCodeCapture {

    private final Map<UUID, String> issuedCodesByChallengeId = new ConcurrentHashMap<>();

    @EventListener
    void onChallengeIssued(ChallengeIssued event) {
        issuedCodesByChallengeId.put(event.challengeId(), event.issuedCode());
    }

    String consume(UUID challengeId) {
        return issuedCodesByChallengeId.remove(challengeId);
    }
}
