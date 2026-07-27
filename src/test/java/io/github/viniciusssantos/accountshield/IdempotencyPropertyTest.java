package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.protection.ConflictingIdempotencyRequestException;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Instant;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Issue #53: "equivalent idempotent retries return the same result" and "key reuse with a
 * different payload conflicts." Uses jqwik's {@code Arbitraries} as a source of varied-but-valid
 * generated inputs inside an ordinary {@code @SpringBootTest}, rather than a jqwik {@code
 * @Property} method -- jqwik's own JUnit5 test engine has no established integration with Spring's
 * test context lifecycle in this codebase, and this property specifically needs the real,
 * Postgres-backed {@code IdempotencyGuard}, not a mocked one, so a real Spring context is
 * required. {@link RepeatedTest} plus a fresh jqwik sample each repetition gives bounded (not
 * unbounded/hanging) coverage across generated inputs while keeping the property test inside
 * this codebase's existing, proven Spring integration-test pattern.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class IdempotencyPropertyTest {

    private static final int REPETITIONS = 20;

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    private final Arbitrary<RiskSignals> signalsArbitrary = Arbitraries.integers().between(0, 20)
            .flatMap(failedAttempts -> Arbitraries.of(NetworkRiskLevel.values())
                    .map(level -> new RiskSignals(failedAttempts, false, false, false, level)));

    @RepeatedTest(REPETITIONS)
    void identicalRetryWithTheSameKeyReturnsTheSameResult() {
        RiskSignals signals = signalsArbitrary.sample();
        String accountReference = "acct-idem-eq-" + UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID();
        ProtectionDecisionCommand command = new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(signals, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                idempotencyKey);

        ProtectionDecisionResult first = protectionDecisionService.decide(command);
        ProtectionDecisionResult second = protectionDecisionService.decide(command);

        assertThat(second.decisionId()).isEqualTo(first.decisionId());
        assertThat(second.protectionRequestId()).isEqualTo(first.protectionRequestId());
        assertThat(second.outcome()).isEqualTo(first.outcome());
    }

    @RepeatedTest(REPETITIONS)
    void reusingTheSameKeyWithADifferentPayloadConflicts() {
        RiskSignals signals = signalsArbitrary.sample();
        String idempotencyKey = "idem-conflict-" + UUID.randomUUID();
        ProtectionDecisionCommand original = new ProtectionDecisionCommand(
                "acct-idem-conflict-a-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(signals, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                idempotencyKey);
        protectionDecisionService.decide(original);

        // same idempotency key, different account reference -> different request fingerprint
        ProtectionDecisionCommand conflicting = new ProtectionDecisionCommand(
                "acct-idem-conflict-b-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(signals, "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                idempotencyKey);

        assertThatThrownBy(() -> protectionDecisionService.decide(conflicting))
                .isInstanceOf(ConflictingIdempotencyRequestException.class);
    }
}
