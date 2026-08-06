package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.challenge.ChallengeUseRejectedException;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.challenge.ConsumeChallengeCommand;
import io.github.viniciusssantos.accountshield.challenge.InvalidChallengeStateException;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanEntity;
import io.github.viniciusssantos.accountshield.challenge.internal.persistence.ChallengePlanRepository;
import java.time.Instant;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #53: "terminal states never transition backwards" and "consumed challenges cannot be
 * reused." Randomized (jqwik-generated attempt counts), sequential exercise of the challenge state
 * machine's terminal edges -- a different angle than {@code ChallengeApplicationServiceTest}'s
 * hand-picked examples or {@code ChallengeConcurrencyTest}'s concurrent-racing coverage (issue
 * #39): this asserts that once CONSUMED or FAILED, no later operation -- with any input -- can
 * ever move the challenge to a different state again.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class ChallengeStateMachinePropertyTest {

    private static final int REPETITIONS = 20;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private ChallengePlanRepository challengePlanRepository;

    @Autowired
    private HmacChallengeCodeHasher codeHasher;

    private final Arbitrary<Short> maxAttemptsArbitrary = Arbitraries.shorts().between((short) 1, (short) 5);

    @RepeatedTest(REPETITIONS)
    void aConsumedChallengeCanNeverBeConsumedOrVerifiedAgainRegardlessOfInput() {
        short maxAttempts = maxAttemptsArbitrary.sample();
        String code = "code-" + UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        String accountReference = "acct-state-machine-" + challengeId;
        seedChallengedPlan(challengeId, contextId, accountReference, maxAttempts, code);

        challengeService.verify(new ChallengeVerificationCommand(
                challengeId, code, ChallengePurpose.PROTECTION_STEP_UP, contextId));
        challengeService.consume(new ConsumeChallengeCommand(
                challengeId, accountReference, ChallengePurpose.PROTECTION_STEP_UP, contextId));

        assertThat(statusOf(challengeId)).isEqualTo(ChallengeStatus.CONSUMED.name());

        // no later operation, with any input, can move a CONSUMED challenge anywhere else
        assertThatThrownBy(() -> challengeService.consume(new ConsumeChallengeCommand(
                        challengeId, accountReference, ChallengePurpose.PROTECTION_STEP_UP, contextId)))
                .isInstanceOf(ChallengeUseRejectedException.class);
        assertThatThrownBy(() -> challengeService.verify(new ChallengeVerificationCommand(
                        challengeId, code, ChallengePurpose.PROTECTION_STEP_UP, contextId)))
                .isInstanceOf(ChallengeUseRejectedException.class);
        assertThat(statusOf(challengeId)).isEqualTo(ChallengeStatus.CONSUMED.name());
    }

    @RepeatedTest(REPETITIONS)
    void exhaustingAttemptsTerminatesInFailedAndNeverAllowsLaterSuccessEvenWithTheCorrectCode() {
        short maxAttempts = maxAttemptsArbitrary.sample();
        String code = "code-" + UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        String accountReference = "acct-state-machine-fail-" + challengeId;
        seedChallengedPlan(challengeId, contextId, accountReference, maxAttempts, code);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            challengeService.verify(new ChallengeVerificationCommand(
                    challengeId, "definitely-wrong-" + attempt, ChallengePurpose.PROTECTION_STEP_UP, contextId));
        }

        assertThat(statusOf(challengeId)).isEqualTo(ChallengeStatus.FAILED.name());

        // FAILED is terminal -- even the genuinely correct code can never succeed afterward
        assertThatThrownBy(() -> challengeService.verify(new ChallengeVerificationCommand(
                        challengeId, code, ChallengePurpose.PROTECTION_STEP_UP, contextId)))
                .isInstanceOf(InvalidChallengeStateException.class);
        assertThatThrownBy(() -> challengeService.consume(new ConsumeChallengeCommand(
                        challengeId, accountReference, ChallengePurpose.PROTECTION_STEP_UP, contextId)))
                .isInstanceOf(InvalidChallengeStateException.class);
        assertThat(statusOf(challengeId)).isEqualTo(ChallengeStatus.FAILED.name());
    }

    private String statusOf(UUID challengeId) {
        return challengePlanRepository.findById(challengeId).orElseThrow().getStatus();
    }

    private void seedChallengedPlan(
            UUID challengeId, UUID contextId, String accountReference, short maxAttempts, String code) {
        Instant now = Instant.now();
        challengePlanRepository.save(new ChallengePlanEntity(
                challengeId, accountReference, ChallengeType.TOTP_SIMULATED.name(),
                ChallengePurpose.PROTECTION_STEP_UP.name(), contextId, ChallengeStatus.CHALLENGED.name(),
                maxAttempts, maxAttempts, codeHasher.hash(code), now, now.plusSeconds(600), null));
    }
}
