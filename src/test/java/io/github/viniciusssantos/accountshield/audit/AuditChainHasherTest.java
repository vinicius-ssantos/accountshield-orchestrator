package io.github.viniciusssantos.accountshield.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainHasherTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final UUID PROTECTION_REQUEST_ID = UUID.randomUUID();
    private static final List<DecisionReasonContribution> REASONS = List.of(
            new DecisionReasonContribution("HIGH_RISK", 20, Map.of()));

    @Test
    void sameInputsProduceTheSameHash() {
        String first = hash("prev-hash");
        String second = hash("prev-hash");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentPreviousHashProducesADifferentHash() {
        assertThat(hash("prev-hash-a")).isNotEqualTo(hash("prev-hash-b"));
    }

    @Test
    void differentReasonsProduceADifferentHash() {
        String withReasons = AuditChainHasher.computeRecordHash(
                AuditChainHasher.CANONICAL_SCHEMA_VERSION, 1, null, DECISION_ID, PROTECTION_REQUEST_ID,
                "acct-1", "fp-1", "risk-1.0", "policy-1", "1.0.0", "ALLOW", 10, DECIDED_AT, REASONS);
        String withoutReasons = AuditChainHasher.computeRecordHash(
                AuditChainHasher.CANONICAL_SCHEMA_VERSION, 1, null, DECISION_ID, PROTECTION_REQUEST_ID,
                "acct-1", "fp-1", "risk-1.0", "policy-1", "1.0.0", "ALLOW", 10, DECIDED_AT, List.of());

        assertThat(withReasons).isNotEqualTo(withoutReasons);
    }

    @Test
    void nullPreviousHashIsAcceptedForTheFirstLink() {
        assertThat(hash(null)).isNotBlank();
    }

    @Test
    void unknownCanonicalSchemaVersionThrows() {
        assertThatThrownBy(() -> AuditChainHasher.computeRecordHash(
                "audit-chain-99.9", 1, null, DECISION_ID, PROTECTION_REQUEST_ID,
                "acct-1", "fp-1", "risk-1.0", "policy-1", "1.0.0", "ALLOW", 10, DECIDED_AT, REASONS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String hash(String previousHash) {
        return AuditChainHasher.computeRecordHash(
                AuditChainHasher.CANONICAL_SCHEMA_VERSION, 1, previousHash, DECISION_ID, PROTECTION_REQUEST_ID,
                "acct-1", "fp-1", "risk-1.0", "policy-1", "1.0.0", "ALLOW", 10, DECIDED_AT, REASONS);
    }
}
