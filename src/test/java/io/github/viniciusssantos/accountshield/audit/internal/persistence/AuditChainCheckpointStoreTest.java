package io.github.viniciusssantos.accountshield.audit.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.PostgreSqlTestConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #150 / F-22: two interleaved read-verify-advance cycles could previously commit in an
 * order that overwrote a higher already-committed {@code last_verified_sequence} with a lower
 * one, regressing ADR 0027's forward-only checkpoint guarantee. {@code advanceTo} is now
 * monotonic via {@code GREATEST(...)} regardless of call order.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class AuditChainCheckpointStoreTest {

    @Autowired
    private AuditChainCheckpointStore checkpointStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // The checkpoint is a single well-known row (id = 1) shared across this whole Testcontainers
    // instance -- reset it directly (bypassing the store's own monotonic advanceTo) so each test
    // starts from a known baseline regardless of what earlier tests already advanced it to.
    @BeforeEach
    void resetCheckpoint() {
        jdbcTemplate.update("UPDATE audit.chain_verification_checkpoint SET last_verified_sequence = 0 WHERE id = 1");
    }

    @Test
    void advanceToNeverRegressesTheCheckpointRegardlessOfCallOrder() {
        checkpointStore.advanceTo(200, Instant.now());
        assertThat(checkpointStore.lastVerifiedSequence()).isEqualTo(200);

        // Simulates a second, slower tick that started before the first one committed and is
        // now applying its own (lower) result after the first already advanced further.
        checkpointStore.advanceTo(100, Instant.now());

        assertThat(checkpointStore.lastVerifiedSequence()).isEqualTo(200);
    }

    @Test
    void advanceToStillAdvancesWhenTheNewSequenceIsHigher() {
        checkpointStore.advanceTo(50, Instant.now());
        checkpointStore.advanceTo(75, Instant.now());

        assertThat(checkpointStore.lastVerifiedSequence()).isEqualTo(75);
    }
}
