package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.audit.AuditChainHasher;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class AuditChainVerificationServiceTest {

    @Autowired
    private AuditChainVerificationService verificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void validChainVerifiesClean() {
        Tip tip = currentTip();
        String hash1 = insertTraceRow(tip.sequence() + 1, tip.hash(), false);
        insertTraceRow(tip.sequence() + 2, hash1, false);

        AuditChainVerificationResult result = verificationService.verifyRange(tip.sequence() + 1, tip.sequence() + 2);

        assertThat(result.valid()).isTrue();
        assertThat(result.breaks()).isEmpty();
        assertThat(result.recordsChecked()).isEqualTo(2);
    }

    @Test
    void tamperedRecordHashIsDetected() {
        Tip tip = currentTip();
        insertTraceRow(tip.sequence() + 1, tip.hash(), true);

        AuditChainVerificationResult result = verificationService.verifyRange(tip.sequence() + 1, tip.sequence() + 1);

        assertThat(result.valid()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().getFirst().reason()).contains("record_hash");
    }

    @Test
    void brokenPreviousHashLinkIsDetected() {
        Tip tip = currentTip();
        insertTraceRow(tip.sequence() + 1, randomHexHash(), false);

        AuditChainVerificationResult result = verificationService.verifyRange(tip.sequence() + 1, tip.sequence() + 1);

        assertThat(result.valid()).isFalse();
        assertThat(result.breaks().getFirst().reason()).contains("previous_hash");
    }

    @Test
    void missingRecordViaDeletionIsDetected() {
        Tip tip = currentTip();
        String hash1 = insertTraceRow(tip.sequence() + 1, tip.hash(), false);
        insertTraceRow(tip.sequence() + 2, hash1, false);

        // Simulates an out-of-band physical deletion bypassing the append-only trigger -- exactly
        // the elevated-access threat model hash chaining defends against, since the trigger alone
        // only stops ordinary application-level DML.
        jdbcTemplate.execute("ALTER TABLE audit.decision_trace DISABLE TRIGGER trg_decision_trace_append_only");
        try {
            jdbcTemplate.update("DELETE FROM audit.decision_trace WHERE chain_sequence = ?", tip.sequence() + 1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE audit.decision_trace ENABLE TRIGGER trg_decision_trace_append_only");
        }

        AuditChainVerificationResult result = verificationService.verifyRange(tip.sequence() + 1, tip.sequence() + 2);

        assertThat(result.valid()).isFalse();
        assertThat(result.breaks()).isNotEmpty();
    }

    @Test
    void unknownCanonicalSchemaVersionIsDetected() {
        Tip tip = currentTip();
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        String accountReference = "chain-test-" + decisionId;
        Instant decidedAt = Instant.now();

        insertProtectionRequest(protectionRequestId, accountReference, decisionId, decidedAt);
        jdbcTemplate.update(
                """
                INSERT INTO audit.decision_trace (
                    id, protection_request_id, account_reference, request_fingerprint,
                    algorithm_version, policy_key, policy_version, outcome, risk_score,
                    normalized_context, decided_at, chain_sequence, previous_hash, record_hash,
                    hash_algorithm, canonical_schema_version
                ) VALUES (?, ?, ?, ?, 'risk-rules-1.0', 'account-protection-default', '1.0.0',
                          'ALLOW', 10, '{}'::jsonb, ?, ?, ?, 'deadbeef', 'SHA-256', 'audit-chain-99.9')
                """,
                decisionId, protectionRequestId, accountReference, "fp-" + decisionId, Timestamp.from(decidedAt),
                tip.sequence() + 1, tip.hash());

        AuditChainVerificationResult result = verificationService.verifyRange(tip.sequence() + 1, tip.sequence() + 1);

        assertThat(result.valid()).isFalse();
    }

    // A fresh random value each call -- never a fixed literal -- so one test's deliberately wrong
    // hash can never accidentally equal another test's, given they share one global chain tip.
    private static String randomHexHash() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private Tip currentTip() {
        return verificationService.currentRootHash()
                .map(root -> new Tip(root.chainSequence(), root.recordHash()))
                .orElse(new Tip(0L, null));
    }

    private String insertTraceRow(long sequence, String previousHash, boolean tamperRecordHash) {
        UUID protectionRequestId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Instant decidedAt = Instant.now();
        String accountReference = "chain-test-" + decisionId;
        String requestFingerprint = "fp-" + decisionId;

        insertProtectionRequest(protectionRequestId, accountReference, decisionId, decidedAt);

        String correctHash = AuditChainHasher.computeRecordHash(
                AuditChainHasher.CANONICAL_SCHEMA_VERSION, sequence, previousHash, decisionId, protectionRequestId,
                accountReference, requestFingerprint, "risk-rules-1.0", "account-protection-default", "1.0.0",
                "ALLOW", 10, decidedAt, List.of());
        String storedHash = tamperRecordHash ? randomHexHash() : correctHash;

        jdbcTemplate.update(
                """
                INSERT INTO audit.decision_trace (
                    id, protection_request_id, account_reference, request_fingerprint,
                    algorithm_version, policy_key, policy_version, outcome, risk_score,
                    normalized_context, decided_at, chain_sequence, previous_hash, record_hash,
                    hash_algorithm, canonical_schema_version
                ) VALUES (?, ?, ?, ?, 'risk-rules-1.0', 'account-protection-default', '1.0.0',
                          'ALLOW', 10, '{}'::jsonb, ?, ?, ?, ?, ?, ?)
                """,
                decisionId, protectionRequestId, accountReference, requestFingerprint, Timestamp.from(decidedAt),
                sequence, previousHash, storedHash, AuditChainHasher.ALGORITHM, AuditChainHasher.CANONICAL_SCHEMA_VERSION);

        return correctHash;
    }

    private void insertProtectionRequest(
            UUID protectionRequestId, String accountReference, UUID decisionId, Instant requestedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO protection.protection_request (
                    id, account_reference, event_type, request_fingerprint, status, requested_at
                ) VALUES (?, ?, 'LOGIN', ?, 'DECIDED', ?)
                """,
                protectionRequestId, accountReference, "fp-" + decisionId, Timestamp.from(requestedAt));
    }

    private record Tip(long sequence, String hash) {
    }
}
