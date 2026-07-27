package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.evidence.EvidenceBundle;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleContent;
import io.github.viniciusssantos.accountshield.evidence.EvidenceBundleService;
import io.github.viniciusssantos.accountshield.evidence.EvidenceExportCommand;
import io.github.viniciusssantos.accountshield.evidence.EvidenceVerificationResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class EvidenceBundleIntegrationTest {

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private EvidenceBundleService evidenceBundleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void exportedBundleIsSelfVerifiable() {
        String accountReference = "evidence-user-" + UUID.randomUUID();
        ProtectionDecisionResult decision = decide(accountReference);

        Optional<EvidenceBundle> bundleOpt = evidenceBundleService.exportBundle(
                new EvidenceExportCommand(decision.protectionRequestId(), "operator-1", "incident review"));

        assertThat(bundleOpt).isPresent();
        EvidenceBundle bundle = bundleOpt.get();
        assertThat(bundle.content().protectionRequestId()).isEqualTo(decision.protectionRequestId());
        assertThat(bundle.manifest().exportedBy()).isEqualTo("operator-1");
        assertThat(bundle.manifest().exportReason()).isEqualTo("incident review");

        EvidenceVerificationResult result = evidenceBundleService.verify(bundle);
        assertThat(result.valid()).isTrue();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    @Transactional
    void rawAccountReferenceNeverAppearsInTheBundle() {
        String accountReference = "evidence-redact-" + UUID.randomUUID();
        ProtectionDecisionResult decision = decide(accountReference);

        EvidenceBundle bundle = evidenceBundleService.exportBundle(
                new EvidenceExportCommand(decision.protectionRequestId(), "operator-1", "privacy check"))
                .orElseThrow();

        assertThat(bundle.content().pseudonymizedAccountReference()).isNotEqualTo(accountReference);
        assertThat(bundle.content().toString()).doesNotContain(accountReference);
    }

    @Test
    @Transactional
    void sameHistoricalDecisionProducesEquivalentCanonicalContentAcrossExports() {
        String accountReference = "evidence-repeat-" + UUID.randomUUID();
        ProtectionDecisionResult decision = decide(accountReference);
        EvidenceExportCommand firstExport = new EvidenceExportCommand(
                decision.protectionRequestId(), "operator-1", "first export");
        EvidenceExportCommand secondExport = new EvidenceExportCommand(
                decision.protectionRequestId(), "operator-2", "second export, different actor/reason");

        EvidenceBundle first = evidenceBundleService.exportBundle(firstExport).orElseThrow();
        EvidenceBundle second = evidenceBundleService.exportBundle(secondExport).orElseThrow();

        // content (and therefore its hash) is reproducible regardless of who exported it or why --
        // only the manifest's actor/reason/timestamp differ between the two exports.
        assertThat(second.content()).isEqualTo(first.content());
        assertThat(second.manifest().contentHash()).isEqualTo(first.manifest().contentHash());
        assertThat(second.manifest().exportedBy()).isNotEqualTo(first.manifest().exportedBy());
    }

    @Test
    @Transactional
    void tamperedContentFailsVerification() {
        String accountReference = "evidence-tamper-" + UUID.randomUUID();
        ProtectionDecisionResult decision = decide(accountReference);
        EvidenceBundle bundle = evidenceBundleService.exportBundle(
                new EvidenceExportCommand(decision.protectionRequestId(), "operator-1", "tamper check"))
                .orElseThrow();

        EvidenceBundle tampered = new EvidenceBundle(
                bundle.manifest(),
                new EvidenceBundleContent(
                        bundle.content().bundleSchemaVersion(),
                        bundle.content().decisionId(),
                        bundle.content().protectionRequestId(),
                        bundle.content().pseudonymizedAccountReference(),
                        bundle.content().requestFingerprint(),
                        bundle.content().algorithmVersion(),
                        bundle.content().policyKey(),
                        bundle.content().policyVersion(),
                        "TEMPORARILY_BLOCK",
                        99,
                        bundle.content().normalizedContext(),
                        bundle.content().decidedAt(),
                        bundle.content().reasons(),
                        bundle.content().replay(),
                        bundle.content().chainProof()));

        EvidenceVerificationResult result = evidenceBundleService.verify(tampered);

        assertThat(result.valid()).isFalse();
        assertThat(result.problems()).isNotEmpty();
    }

    @Test
    @Transactional
    void exportIsRecordedInTheAuditLog() {
        String accountReference = "evidence-log-" + UUID.randomUUID();
        ProtectionDecisionResult decision = decide(accountReference);

        EvidenceBundle bundle = evidenceBundleService.exportBundle(
                new EvidenceExportCommand(decision.protectionRequestId(), "operator-log", "log check"))
                .orElseThrow();

        Long matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit.evidence_export_log WHERE decision_id = ? "
                        + "AND exported_by = ? AND export_reason = ? AND content_hash = ?",
                Long.class,
                bundle.content().decisionId(), "operator-log", "log check", bundle.manifest().contentHash());
        assertThat(matches).isEqualTo(1L);
    }

    @Test
    @Transactional
    void exportIsEmptyWhenNoDecisionExistsForTheProtectionRequest() {
        Optional<EvidenceBundle> bundleOpt = evidenceBundleService.exportBundle(
                new EvidenceExportCommand(UUID.randomUUID(), "operator-1", "no such decision"));

        assertThat(bundleOpt).isEmpty();
    }

    private ProtectionDecisionResult decide(String accountReference) {
        return protectionDecisionService.decide(new ProtectionDecisionCommand(
                accountReference,
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(
                        new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                        "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                "idem-" + UUID.randomUUID()));
    }
}
