package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.recovery.InitiateRecoveryCommand;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlow;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.RecoveryFlowDetail;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowDetailQuery.SectionAvailability;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchCriteria;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchPage;
import io.github.viniciusssantos.accountshield.recovery.RecoveryFlowSearchQuery.RecoveryFlowSearchSummary;
import io.github.viniciusssantos.accountshield.recovery.RecoveryService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every search assertion bounds {@code initiatedFrom} to a window captured immediately before
 * the test's own fixtures are created. Recovery flows are not rolled back between tests (see
 * {@code RecoveryIntegrationTest}), so an unbounded query would also match flows persisted by
 * unrelated tests in the same run.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class RecoveryInvestigationIntegrationTest {

    @Autowired private RecoveryService recoveryService;
    @Autowired private RecoveryFlowSearchQuery searchQuery;
    @Autowired private RecoveryFlowDetailQuery detailQuery;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void returnsAStableMinimizedPageOrderedByUpdatedAtDescending() {
        Instant windowStart = Instant.now().minusSeconds(1);
        RecoveryFlow first = initiate(10, "LOGIN");
        RecoveryFlow second = initiate(15, "LOGIN");

        RecoveryFlowSearchPage page = searchQuery.search(windowCriteria(windowStart, "LOGIN", 100));

        assertThat(page.recoveries())
                .extracting(RecoveryFlowSearchSummary::recoveryReference)
                .contains(first.recoveryId().toString(), second.recoveryId().toString());
    }

    @Test
    void paginatesWithAStableOpaqueCursor() {
        Instant windowStart = Instant.now().minusSeconds(1);
        RecoveryFlow first = initiate(5, "DEVICE_TRUST_RESET");
        RecoveryFlow second = initiate(6, "DEVICE_TRUST_RESET");
        assertThat(first.recoveryId()).isNotEqualTo(second.recoveryId());

        RecoveryFlowSearchPage page1 = searchQuery.search(
                windowCriteria(windowStart, "DEVICE_TRUST_RESET", 1));
        assertThat(page1.recoveries()).hasSize(1);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        RecoveryFlowSearchPage page2 = searchQuery.search(new RecoveryFlowSearchCriteria(
                null, null, "DEVICE_TRUST_RESET", windowStart, null, null, null, null, null,
                page1.nextCursor(), 25));
        assertThat(page2.recoveries())
                .extracting(RecoveryFlowSearchSummary::recoveryReference)
                .doesNotContain(page1.recoveries().get(0).recoveryReference())
                .contains(first.recoveryId().toString());
    }

    @Test
    void masksTheSubjectReferenceInSearchResults() {
        Instant windowStart = Instant.now().minusSeconds(1);
        String rawAccountReference = "sensitive-account-" + UUID.randomUUID();
        RecoveryFlow flow = initiate(20, "PASSWORD_RESET", rawAccountReference);

        RecoveryFlowSearchPage page = searchQuery.search(
                windowCriteria(windowStart, "PASSWORD_RESET", 100));

        var summary = page.recoveries().stream()
                .filter(item -> item.recoveryReference().equals(flow.recoveryId().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.maskedSubjectReference())
                .startsWith("••••")
                .endsWith(rawAccountReference.substring(rawAccountReference.length() - 4))
                .doesNotContain(rawAccountReference);
        assertThat(summary.classification()).isNotBlank();
    }

    @Test
    void filtersByClassificationAndRiskScoreRange() {
        Instant windowStart = Instant.now().minusSeconds(1);
        RecoveryFlow immediate = initiate(10, "CREDENTIAL_CHANGE");
        RecoveryFlow manualReview = initiate(90, "CREDENTIAL_CHANGE");

        RecoveryFlowSearchPage manualPage = searchQuery.search(new RecoveryFlowSearchCriteria(
                null, "MANUAL_REVIEW", "CREDENTIAL_CHANGE", windowStart, null, null, null, 61, 100,
                null, 100));

        assertThat(manualPage.recoveries())
                .extracting(RecoveryFlowSearchSummary::recoveryReference)
                .contains(manualReview.recoveryId().toString())
                .doesNotContain(immediate.recoveryId().toString());
    }

    @Test
    void returnsMinimizedDetailWithMaskedCrossModuleReferencesAndChallengeSummary() {
        String rawAccountReference = "sensitive-detail-" + UUID.randomUUID();
        RecoveryFlow flow = initiate(25, "LOGIN", rawAccountReference);

        RecoveryFlowDetail detail = detailQuery.investigate(flow.recoveryId().toString()).orElseThrow();

        assertThat(detail.recoveryReference()).isEqualTo(flow.recoveryId().toString());
        assertThat(detail.maskedSubjectReference())
                .startsWith("••••")
                .endsWith(rawAccountReference.substring(rawAccountReference.length() - 4))
                .doesNotContain(rawAccountReference);
        assertThat(detail.maskedOriginatingDecisionReference())
                .startsWith("••••")
                .doesNotContain(flow.originatingDecisionId().toString());
        assertThat(detail.maskedProtectionRequestReference())
                .startsWith("••••")
                .doesNotContain(flow.protectionRequestId().toString());
        assertThat(detail.status()).isEqualTo("VERIFYING_IDENTITY");
        assertThat(detail.terminal()).isFalse();
        assertThat(detail.terminalAt()).isNull();
        assertThat(detail.challenges())
                .singleElement()
                .satisfies(challenge -> {
                    assertThat(challenge.purpose()).isEqualTo("RECOVERY_IDENTITY");
                    assertThat(challenge.reference()).isEqualTo(flow.identityChallengeId().toString());
                });
        assertThat(detail.challengeSection()).isEqualTo(SectionAvailability.AVAILABLE);
        assertThat(detail.partial()).isFalse();
    }

    @Test
    void rejectsMalformedReferencesWithoutExposingPersistenceDetails() {
        assertThatThrownBy(() -> searchQuery.search(new RecoveryFlowSearchCriteria(
                null, null, null, null, null, null, null, null, null, "not-a-valid-cursor", 25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid recovery search cursor");
        assertThatThrownBy(() -> detailQuery.investigate("not-a-recovery-reference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recoveryReference must be a valid UUID");
    }

    @Test
    void returnsEmptyForAnUnknownButWellFormedReference() {
        assertThat(detailQuery.investigate(UUID.randomUUID().toString())).isEmpty();
    }

    private RecoveryFlowSearchCriteria windowCriteria(Instant initiatedFrom, String eventType, int pageSize) {
        return new RecoveryFlowSearchCriteria(
                null, null, eventType, initiatedFrom, null, null, null, null, null, null, pageSize);
    }

    private RecoveryFlow initiate(int riskScore, String directive) {
        return initiate(riskScore, directive, "recovery-account-" + UUID.randomUUID());
    }

    private RecoveryFlow initiate(int riskScore, String directive, String accountReference) {
        UUID authorizationId = UUID.randomUUID();
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minus(30, ChronoUnit.SECONDS);

        jdbcTemplate.update(
                "INSERT INTO protection.protection_request "
                        + "(id, account_reference, event_type, request_fingerprint, status, requested_at) "
                        + "VALUES (?, ?, ?, ?, 'DECIDED', ?)",
                protectionRequestId,
                accountReference,
                directive,
                "fingerprint-" + protectionRequestId,
                Timestamp.from(issuedAt));

        jdbcTemplate.update(
                "INSERT INTO recovery.recovery_authorization "
                        + "(id, protection_request_id, decision_id, account_reference, directive, "
                        + "risk_score, issued_at, expires_at, consumed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                authorizationId,
                protectionRequestId,
                decisionId,
                accountReference,
                directive,
                riskScore,
                Timestamp.from(issuedAt),
                Timestamp.from(Instant.now().plusSeconds(600)));

        return recoveryService.initiate(new InitiateRecoveryCommand(authorizationId));
    }
}
