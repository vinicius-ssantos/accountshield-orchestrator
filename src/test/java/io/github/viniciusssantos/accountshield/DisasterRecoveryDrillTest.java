package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.viniciusssantos.accountshield.audit.AuditChainRootHash;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationResult;
import io.github.viniciusssantos.accountshield.audit.AuditChainVerificationService;
import io.github.viniciusssantos.accountshield.outbox.internal.OutboxRelay;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionResult;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Issue #51: an executable backup/restore/disaster-recovery drill, not a documentation-only
 * procedure. Takes a real {@code pg_dump} of the "source" Testcontainers Postgres instance this
 * test's own {@code @SpringBootTest} context runs against, restores it via {@code psql} into a
 * completely separate, freshly-started "destination" Postgres container, then bootstraps a second,
 * independent Spring context against that restored database (real Flyway startup validation, real
 * beans) to prove every domain invariant issue #51 names still holds. See ADR 0036.
 */
@Tag("disaster-recovery")
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class DisasterRecoveryDrillTest {

    private static final Path REPORT_PATH = Path.of("target/dr-reports/restore-drill.md");
    private static final int PRE_BACKUP_DECISIONS = 20;
    private static final int POST_BACKUP_DECISIONS = 3;
    private static final String DUMP_CONTAINER_PATH = "/tmp/accountshield-dr-drill-backup.sql";

    @Autowired
    private PostgreSQLContainer sourceContainer;

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate sourceJdbc;

    @Autowired
    private AuditChainVerificationService sourceAuditChain;

    @Test
    void restoreDrillValidatesDomainInvariantsAfterBackupAndRestore() throws Exception {
        // Other test classes sharing this Testcontainers instance (e.g. CapacityBenchmarkTest)
        // leave their own PENDING outbox rows behind. Left alone, those rows would (a) get backed
        // up into this drill's own pg_dump snapshot and legitimately published by the restored
        // relay, inflating the "unchanged after restart" invariant below, and (b) starve
        // outboxRelay.dispatchPending()'s bounded batch below of room for this test's own rows,
        // since the relay claims the oldest pending rows first (issue #164). Scoping the outbox
        // counts to this drill's own decision IDs (see countOutboxPublished) only solves (a); this
        // purge is what actually solves (b), giving this test the clean, fully-owned outbox
        // baseline a disaster-recovery drill should reason about in the first place.
        sourceJdbc.update("DELETE FROM outbox.outbox_event");

        List<UUID> preBackupDecisionIds = seedDecisions(PRE_BACKUP_DECISIONS);
        outboxRelay.dispatchPending();
        int preBackupDecisionCount = countRows(sourceJdbc, "audit.decision_trace");
        // Precondition: the chain must already be valid on the live source before backup is even
        // attempted -- isolates any future chain-verification failure after restore (below) as
        // restore-specific rather than a pre-existing condition on the source itself. Bounded by
        // the actual chain tip (issue #185), not preBackupDecisionCount's raw COUNT(*): the two
        // are not guaranteed equal (chain_sequence is nullable for pre-chain/fixture rows, so
        // COUNT(*) can exceed the real max chain_sequence), and an upper bound past the real tip
        // makes verifyRange silently check fewer rows than intended rather than failing loudly on
        // the actual current state of the chain.
        AuditChainRootHash sourceTip = sourceAuditChain.currentRootHash().orElseThrow();
        AuditChainVerificationResult sourceChainResult = sourceAuditChain.verifyRange(1, sourceTip.chainSequence());
        assertThat(sourceChainResult.valid())
                .as("source chain must verify cleanly before backup is attempted: %s", sourceChainResult.breaks())
                .isTrue();
        // Scoped to only the events this test itself created (issue #164): the unscoped
        // "count(*) where status = 'PUBLISHED'" this replaced also counted other test classes'
        // leftover PENDING outbox rows once they got swept into this drill's own pg_dump backup
        // and published by the restored relay below, inflating the "must be unchanged after
        // restart" assertion far past what this drill actually seeded.
        int preBackupPublishedOutbox = countOutboxPublished(sourceJdbc, preBackupDecisionIds);
        assertThat(preBackupPublishedOutbox)
                .as("every seeded decision must produce exactly one published outbox event")
                .isEqualTo(PRE_BACKUP_DECISIONS);

        Path dumpFile = Files.createTempFile("accountshield-dr-drill-backup", ".sql");
        Instant backupStart = Instant.now();
        ExecResult dumpResult = sourceContainer.execInContainer(ExecConfig.builder()
                .command(new String[] {
                    "pg_dump", "-U", sourceContainer.getUsername(), "-d", sourceContainer.getDatabaseName(),
                    "-f", DUMP_CONTAINER_PATH
                })
                .envVars(Map.of("PGPASSWORD", sourceContainer.getPassword()))
                .build());
        assertThat(dumpResult.getExitCode()).as("pg_dump failed: %s", dumpResult.getStderr()).isZero();
        sourceContainer.copyFileFromContainer(DUMP_CONTAINER_PATH, dumpFile.toString());
        Duration backupDuration = Duration.between(backupStart, Instant.now());

        // Simulate operations that happen after the backup point but before a disaster --
        // demonstrates this backup schedule's actual RPO (data loss window): these decisions must
        // NOT appear in the restored database below.
        seedDecisions(POST_BACKUP_DECISIONS);
        assertThat(countRows(sourceJdbc, "audit.decision_trace"))
                .isEqualTo(preBackupDecisionCount + POST_BACKUP_DECISIONS);

        PostgreSQLContainer destinationContainer = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName(sourceContainer.getDatabaseName())
                .withUsername(sourceContainer.getUsername())
                .withPassword(sourceContainer.getPassword());
        destinationContainer.start();
        try {
            Instant restoreStart = Instant.now();
            destinationContainer.copyFileToContainer(MountableFile.forHostPath(dumpFile), DUMP_CONTAINER_PATH);
            ExecResult restoreResult = destinationContainer.execInContainer(ExecConfig.builder()
                    .command(new String[] {
                        "psql", "-U", destinationContainer.getUsername(), "-d", destinationContainer.getDatabaseName(),
                        "-f", DUMP_CONTAINER_PATH
                    })
                    .envVars(Map.of("PGPASSWORD", destinationContainer.getPassword()))
                    .build());
            assertThat(restoreResult.getExitCode())
                    .as("psql restore failed outright: %s", restoreResult.getStderr())
                    .isZero();
            Duration restoreDuration = Duration.between(restoreStart, Instant.now());

            Instant appReadyStart = Instant.now();
            ConfigurableApplicationContext restoredContext = new SpringApplicationBuilder(AccountShieldApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(Map.of(
                            "spring.datasource.url", destinationContainer.getJdbcUrl(),
                            "spring.datasource.username", destinationContainer.getUsername(),
                            "spring.datasource.password", destinationContainer.getPassword()))
                    .run();
            try {
                JdbcTemplate restoredJdbc = restoredContext.getBean(JdbcTemplate.class);

                int sourceMigrationCount = countRows(sourceJdbc, "flyway_schema_history");
                int restoredMigrationCount = countRows(restoredJdbc, "flyway_schema_history");
                assertThat(restoredMigrationCount)
                        .as("Flyway startup against the restored database completed with no pending migrations "
                                + "(context bootstrap above would have failed otherwise) and the migration history "
                                + "is complete")
                        .isEqualTo(sourceMigrationCount);

                AuditChainVerificationService restoredAuditChain = restoredContext.getBean(AuditChainVerificationService.class);
                AuditChainRootHash tip = restoredAuditChain.currentRootHash().orElseThrow();
                AuditChainVerificationResult chainResult = restoredAuditChain.verifyRange(1, tip.chainSequence());
                assertThat(chainResult.valid())
                        .as("audit hash chain must verify cleanly after restore: %s", chainResult.breaks())
                        .isTrue();

                List<String> duplicateActivePolicies = restoredJdbc.queryForList(
                        "select policy_key from policy.policy_version where status = 'ACTIVE' "
                                + "group by policy_key having count(*) > 1",
                        String.class);
                assertThat(duplicateActivePolicies)
                        .as("at most one ACTIVE policy version per policy_key must survive restore")
                        .isEmpty();

                int restoredDecisionCount = countRows(restoredJdbc, "audit.decision_trace");
                assertThat(restoredDecisionCount)
                        .as("restored data must reflect exactly the backup point, not the post-backup activity")
                        .isEqualTo(preBackupDecisionCount);

                int restoredPublishedOutbox = countOutboxPublished(restoredJdbc, preBackupDecisionIds);
                assertThat(restoredPublishedOutbox)
                        .as("previously published outbox events must remain published after restore")
                        .isEqualTo(preBackupPublishedOutbox);
                OutboxRelay restoredRelay = restoredContext.getBean(OutboxRelay.class);
                restoredRelay.dispatchPending();
                assertThat(countOutboxPublished(restoredJdbc, preBackupDecisionIds))
                        .as("restarting the relay against restored data must not re-publish already-published events")
                        .isEqualTo(preBackupPublishedOutbox);

                ProtectionDecisionService restoredDecisionService = restoredContext.getBean(ProtectionDecisionService.class);
                ProtectionDecisionResult smokeResult = restoredDecisionService.decide(decisionCommand("post-restore-smoke", 0));
                assertThat(smokeResult.outcome()).isNotNull();

                Duration appReadyDuration = Duration.between(appReadyStart, Instant.now());
                writeReport(
                        backupDuration, restoreDuration, appReadyDuration,
                        preBackupDecisionCount, restoredDecisionCount, POST_BACKUP_DECISIONS,
                        preBackupPublishedOutbox, restoredPublishedOutbox, restoredMigrationCount,
                        chainResult, dumpResult, restoreResult);
            } finally {
                restoredContext.close();
            }
        } finally {
            destinationContainer.stop();
            Files.deleteIfExists(dumpFile);
        }
    }

    private List<UUID> seedDecisions(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> protectionDecisionService.decide(decisionCommand("seed", i)).decisionId())
                .toList();
    }

    private ProtectionDecisionCommand decisionCommand(String slug, int index) {
        RiskSignalEnvelope envelope = new RiskSignalEnvelope(
                new RiskSignals(index % 4, index % 3 == 0, false, false, NetworkRiskLevel.LOW),
                "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true);
        return new ProtectionDecisionCommand(
                "dr-drill-" + slug + "-" + UUID.randomUUID() + "@example.test",
                ProtectionEventType.LOGIN_ATTEMPT,
                envelope,
                "idem-dr-drill-" + slug + "-" + UUID.randomUUID());
    }

    private int countRows(JdbcTemplate jdbcTemplate, String qualifiedTable) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + qualifiedTable, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Scoped to the given decision IDs' own outbox events (issue #164) -- {@code aggregate_id} for
     * a {@code PROTECTION_DECISION_MADE} event is the decision ID, set by {@code
     * OutboxEventRecorder.onProtectionDecisionMade} -- rather than counting the whole shared
     * {@code outbox.outbox_event} table, which other test classes sharing this Testcontainers
     * instance also write to.
     */
    private int countOutboxPublished(JdbcTemplate jdbcTemplate, List<UUID> decisionIds) {
        if (decisionIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(decisionIds.size(), "?"));
        String sql = "select count(*) from outbox.outbox_event where status = 'PUBLISHED' "
                + "and aggregate_type = 'ProtectionDecision' and aggregate_id in (" + placeholders + ")";
        Object[] args = decisionIds.stream().map(UUID::toString).toArray();
        Integer count = jdbcTemplate.queryForObject(sql, args, Integer.class);
        return count == null ? 0 : count;
    }

    private void writeReport(
            Duration backupDuration, Duration restoreDuration, Duration appReadyDuration,
            int preBackupDecisionCount, int restoredDecisionCount, int lostPostBackupDecisions,
            int preBackupPublishedOutbox, int restoredPublishedOutbox, int restoredMigrationCount,
            AuditChainVerificationResult chainResult, ExecResult dumpResult, ExecResult restoreResult)
            throws Exception {
        Duration totalRto = restoreDuration.plus(appReadyDuration);
        StringBuilder md = new StringBuilder();
        md.append("# AccountShield Disaster Recovery Drill Report\n\n");
        md.append("Generated: ").append(Instant.now()).append("\n\n");
        md.append("Single wall-clock run on this CI/nightly runner's shared hardware -- directional evidence of ")
                .append("procedure shape and cost, not an SLA-grade production RTO/RPO commitment. Reproduce with: ")
                .append("`./mvnw -Dgroups=disaster-recovery test -Dtest=DisasterRecoveryDrillTest`.\n\n");

        md.append("## Measured RTO (Recovery Time Objective)\n\n");
        md.append("| Step | Duration |\n|---|---|\n");
        md.append(String.format(Locale.ROOT, "| Backup (`pg_dump`) | %d ms |%n", backupDuration.toMillis()));
        md.append(String.format(Locale.ROOT, "| Restore (`psql` replay) | %d ms |%n", restoreDuration.toMillis()));
        md.append(String.format(Locale.ROOT,
                "| App ready (context bootstrap + all validations + smoke test) | %d ms |%n",
                appReadyDuration.toMillis()));
        md.append(String.format(Locale.ROOT, "| **Total RTO (restore start -> app serving)** | **%d ms** |%n",
                totalRto.toMillis()));
        md.append('\n');

        md.append("## Measured RPO (Recovery Point Objective)\n\n");
        md.append("Demonstrated, not just claimed: ").append(lostPostBackupDecisions)
                .append(" decisions made *after* the backup point are correctly absent from the restored database ")
                .append("(restored decision_trace row count = ").append(restoredDecisionCount)
                .append(", exactly the pre-backup count of ").append(preBackupDecisionCount)
                .append(", not ").append(preBackupDecisionCount + lostPostBackupDecisions)
                .append("). This backup schedule's RPO in a real deployment equals the interval between backups: ")
                .append("any write after the most recent backup and before a disaster is lost, exactly as shown ")
                .append("here for a single backup cycle.\n\n");

        md.append("## Domain invariants validated after restore\n\n");
        md.append("| Invariant | Result |\n|---|---|\n");
        md.append("| Flyway migration history complete (no drift) | ").append(restoredMigrationCount)
                .append(" migrations, matching source |\n");
        md.append("| Audit hash chain verifies | valid=").append(chainResult.valid())
                .append(", recordsChecked=").append(chainResult.recordsChecked()).append(" |\n");
        md.append("| Active-policy uniqueness (no duplicate ACTIVE per policy_key) | held |\n");
        md.append("| Outbox: previously published events remain published, no unintended republish | ")
                .append(preBackupPublishedOutbox).append(" before, ").append(restoredPublishedOutbox)
                .append(" after (unchanged) |\n");
        md.append("| Post-restore smoke test (`decide()` against restored app) | succeeded |\n\n");

        md.append("## Secrets and key recovery considerations\n\n");
        md.append("- The envelope-encryption KEK secret (`accountshield.crypto.*`, ADR 0025) is **not** part of a ")
                .append("`pg_dump` data backup at all -- it lives in application configuration/secrets management, ")
                .append("not the database. This drill's restored context only decrypts `account_reference` correctly ")
                .append("because it shares the same classpath configuration as the source; a real disaster-recovery ")
                .append("procedure must independently ensure the KEK secret is available wherever the restored ")
                .append("database is served from, or every encrypted field becomes permanently unreadable.\n")
                .append("- The database-role grants introduced by migration V20 (`accountshield_runtime`, ")
                .append("`accountshield_readonly`, ADR 0024) are cluster-level roles, not part of the dumped ")
                .append("database's data -- a raw `pg_dump`/`psql` restore into a cluster that has never run that ")
                .append("migration's `CREATE ROLE`/`GRANT` statements will emit \"role does not exist\" errors for ")
                .append("those specific statements (see restore stderr excerpt below) while table data and structure ")
                .append("restore correctly regardless. A real restore procedure must re-run the full migration set ")
                .append("(or at minimum V20) against a fresh cluster before -- or as part of -- restoring data, not ")
                .append("rely on a bare data dump alone.\n\n");

        md.append("## Restore stderr excerpt (informational, not asserted empty -- see above)\n\n");
        md.append("```\n").append(truncate(restoreResult.getStderr(), 2000)).append("\n```\n\n");

        if (!dumpResult.getStderr().isBlank()) {
            md.append("## Backup stderr excerpt\n\n```\n").append(truncate(dumpResult.getStderr(), 1000)).append("\n```\n\n");
        }

        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, md.toString());
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n... (truncated)";
    }
}
