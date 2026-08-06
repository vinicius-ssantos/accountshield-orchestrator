package io.github.viniciusssantos.accountshield.recovery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import io.github.viniciusssantos.accountshield.recovery.InvalidRecoveryStateException;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.recovery.RecoveryStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #39: "clock boundaries for delayed recovery." {@code RecoveryApplicationService.complete}
 * gates a DELAYED flow on {@code now.isBefore(entity.getEligibleAfter())} -- this proves both
 * sides of that boundary against real elapsed time (no clock mocking exists anywhere in this test
 * suite's established conventions, so this uses a controlled {@code eligible_after} relative to
 * the real clock rather than introducing new clock-injection infrastructure): a flow whose
 * eligibility window has not yet opened is rejected, and the identical flow past that instant is
 * accepted.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryClockBoundaryTest {

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void completingBeforeEligibleAfterIsRejected() {
        UUID recoveryId = seedDelayedFlow(Instant.now().plus(Duration.ofSeconds(30)));

        assertThatThrownBy(() -> recoveryService.complete(recoveryId))
                .isInstanceOf(InvalidRecoveryStateException.class);

        assertThat(statusOf(recoveryId)).isEqualTo(RecoveryStatus.DELAYED.name());
    }

    @Test
    void completingAtOrAfterEligibleAfterSucceeds() {
        // eligible_after already in the past relative to the moment complete() actually runs --
        // the real clock has necessarily moved forward since this row was seeded, so this
        // deterministically lands on the "at or after" side of the boundary.
        UUID recoveryId = seedDelayedFlow(Instant.now().minusMillis(1));

        var result = recoveryService.complete(recoveryId);

        assertThat(result.status()).isEqualTo(RecoveryStatus.COMPLETED);
        assertThat(statusOf(recoveryId)).isEqualTo(RecoveryStatus.COMPLETED.name());
    }

    private String statusOf(UUID recoveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM recovery.recovery_flow WHERE id = ?", String.class, recoveryId);
    }

    private UUID seedDelayedFlow(Instant eligibleAfter) {
        UUID recoveryId = UUID.randomUUID();
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        Instant now = Instant.now();
        String accountReference = "acct-clock-boundary-" + recoveryId;

        jdbcTemplate.update(
                """
                INSERT INTO protection.protection_request (
                    id, account_reference, event_type, request_fingerprint, status, requested_at
                ) VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)
                """,
                protectionRequestId, accountReference, "fingerprint-" + recoveryId, Timestamp.from(now));

        jdbcTemplate.update(
                """
                INSERT INTO audit.decision_trace (
                    id, protection_request_id, account_reference, request_fingerprint,
                    algorithm_version, policy_key, policy_version, outcome, risk_score,
                    normalized_context, decided_at
                ) VALUES (?, ?, ?, ?, 'risk-rules-1.0', 'account-protection-default', '1.0.0',
                          'START_RECOVERY', 45, '{}'::jsonb, ?)
                """,
                decisionId, protectionRequestId, accountReference, "fingerprint-decision-" + recoveryId,
                Timestamp.from(now));

        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_authorization (
                    id, protection_request_id, decision_id, account_reference, directive,
                    risk_score, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, 'LOGIN', 45, ?, ?, NULL)
                """,
                authorizationId, protectionRequestId, decisionId, accountReference,
                Timestamp.from(now), Timestamp.from(now.plusSeconds(600)));

        jdbcTemplate.update(
                """
                INSERT INTO recovery.recovery_flow (
                    id, account_reference, event_type, status, classification,
                    risk_score, initiated_at, updated_at, eligible_after, protection_request_id,
                    originating_decision_id, authorization_id
                ) VALUES (?, ?, 'LOGIN', ?, 'DELAYED', 45, ?, ?, ?, ?, ?, ?)
                """,
                recoveryId, accountReference, RecoveryStatus.DELAYED.name(), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(eligibleAfter), protectionRequestId, decisionId,
                authorizationId);

        return recoveryId;
    }
}
