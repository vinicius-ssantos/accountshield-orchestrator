package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.policy.ProtectionOutcome;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryCriteria;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.RecoveryPage;
import io.github.viniciusssantos.accountshield.recovery.RecoveryOperationsQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryOperationsIntegrationTest {

    private static final Instant WINDOW_START = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2099-01-05T00:00:00Z");

    @Autowired private ProtectionDecisionService protectionDecisionService;
    @Autowired private RecoveryService recoveryService;
    @Autowired private RecoveryOperationsQuery operationsQuery;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void returnsStableFilteredCursorPagesAndPrivacyMinimizedDetail() {
        RecoveryFlow delayed = createFlow("delayed-sensitive-" + UUID.randomUUID());
        RecoveryFlow manual = createFlow("manual-sensitive-" + UUID.randomUUID());
        RecoveryFlow completed = createFlow("completed-sensitive-" + UUID.randomUUID());

        updateFlow(
                delayed.recoveryId(),
                "DELAYED",
                "DELAYED",
                45,
                Instant.parse("2099-01-03T00:00:00Z"),
                Instant.parse("2099-01-04T00:00:00Z"),
                null);
        updateFlow(
                manual.recoveryId(),
                "MANUAL_REVIEW",
                "MANUAL_REVIEW",
                75,
                Instant.parse("2099-01-02T00:00:00Z"),
                null,
                null);
        updateFlow(
                completed.recoveryId(),
                "COMPLETED",
                "IMMEDIATE",
                20,
                Instant.parse("2099-01-01T12:00:00Z"),
                null,
                "operator-reviewer-must-not-leak");

        RecoveryPage first = operationsQuery.search(criteria(null, null, null, 1));
        RecoveryPage second = operationsQuery.search(criteria(null, null, first.nextCursor(), 1));

        assertThat(first.recoveries()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.recoveries()).hasSize(1);
        assertThat(second.recoveries().getFirst().recoveryReference())
                .isNotEqualTo(first.recoveries().getFirst().recoveryReference());
        Set<String> references = new HashSet<>();
        references.add(first.recoveries().getFirst().recoveryReference());
        references.add(second.recoveries().getFirst().recoveryReference());
        assertThat(references).hasSize(2);

        RecoveryPage pendingReview = operationsQuery.search(criteria(
                "MANUAL_REVIEW", "PENDING", null, 25));
        assertThat(pendingReview.recoveries())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.recoveryReference()).isEqualTo(manual.recoveryId().toString());
                    assertThat(summary.reviewState()).isEqualTo("PENDING");
                    assertThat(summary.riskScore()).isEqualTo(75);
                    assertThat(summary.maskedSubjectReference())
                            .startsWith("••••")
                            .doesNotContain("manual-sensitive");
                });

        RecoveryDetail detail = operationsQuery
                .investigate(delayed.recoveryId().toString())
                .orElseThrow();
        assertThat(detail.recovery().recoveryReference()).isEqualTo(delayed.recoveryId().toString());
        assertThat(detail.recovery().status()).isEqualTo("DELAYED");
        assertThat(detail.recovery().terminal()).isFalse();
        assertThat(detail.reviewerPresent()).isFalse();
        assertThat(detail.challengeAvailability()).isEqualTo(SectionAvailability.AVAILABLE);
        assertThat(detail.challenges()).singleElement().satisfies(challenge -> {
            assertThat(challenge.reference()).isEqualTo(delayed.identityChallengeId().toString());
            assertThat(challenge.purpose()).isEqualTo("RECOVERY_IDENTITY");
            assertThat(challenge.status()).isEqualTo("CHALLENGED");
        });
        assertThat(detail.partial()).isFalse();
    }

    @Test
    void rejectsMalformedCursorAndReferenceWithoutPersistenceDetails() {
        assertThatThrownBy(() -> operationsQuery.search(criteria(null, null, "not-a-cursor", 25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid recovery search cursor");
        assertThatThrownBy(() -> operationsQuery.investigate("not-a-reference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recoveryReference must be a valid UUID");
    }

    private RecoveryCriteria criteria(
            String status,
            String reviewState,
            String cursor,
            int pageSize) {
        return new RecoveryCriteria(
                status,
                null,
                null,
                reviewState,
                WINDOW_START,
                WINDOW_END,
                null,
                null,
                null,
                null,
                cursor,
                pageSize);
    }

    private RecoveryFlow createFlow(String accountReference) {
        ProtectionDecisionResult decision = protectionDecisionService.decide(
                new ProtectionDecisionCommand(
                        accountReference,
                        ProtectionEventType.PASSWORD_RESET_ATTEMPT,
                        new RiskSignalEnvelope(
                                new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                                "CLIENT_SUPPLIED",
                                Instant.now(),
                                SignalConfidence.HIGH,
                                null,
                                true),
                        "recovery-operations-" + UUID.randomUUID()));
        assertThat(decision.outcome()).isEqualTo(ProtectionOutcome.START_RECOVERY);
        return recoveryService.initiate(new InitiateRecoveryCommand(
                decision.recoveryAuthorizationId()));
    }

    private void updateFlow(
            UUID recoveryId,
            String status,
            String classification,
            int riskScore,
            Instant updatedAt,
            Instant eligibleAfter,
            String reviewer) {
        jdbcTemplate.update(
                "UPDATE recovery.recovery_flow "
                        + "SET status = ?, classification = ?, risk_score = ?, "
                        + "initiated_at = ?, updated_at = ?, eligible_after = ?, reviewer = ? "
                        + "WHERE id = ?",
                status,
                classification,
                riskScore,
                Timestamp.from(updatedAt.minusSeconds(60)),
                Timestamp.from(updatedAt),
                eligibleAfter == null ? null : Timestamp.from(eligibleAfter),
                reviewer,
                recoveryId);
    }
}
