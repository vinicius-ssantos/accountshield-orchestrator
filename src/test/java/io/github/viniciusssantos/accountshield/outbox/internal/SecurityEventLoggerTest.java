package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.viniciusssantos.accountshield.challenge.ChallengeCompleted;
import io.github.viniciusssantos.accountshield.challenge.ChallengeStatus;
import io.github.viniciusssantos.accountshield.challenge.ChallengeType;
import io.github.viniciusssantos.accountshield.policy.PolicyActivated;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionMade;
import io.github.viniciusssantos.accountshield.recovery.RecoveryCompleted;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecurityEventLoggerTest {

    private SecurityEventLogger logger;
    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        logger = new SecurityEventLogger();
        logbackLogger = (Logger) LoggerFactory.getLogger("accountshield.security");
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsProtectionDecisionWithStructuredFields() {
        logger.onProtectionDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct-sensitive@example.com",
                "REQUIRE_STEP_UP", 78, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:00Z")));

        String formatted = appender.list.getFirst().getFormattedMessage();

        assertThat(formatted).contains("type=PROTECTION_DECISION");
        assertThat(formatted).contains("outcome=REQUIRE_STEP_UP");
        assertThat(formatted).contains("risk_score=78");
        assertThat(formatted).contains("policy=account-protection-default:1.0.0");
    }

    @Test
    void doesNotLogAccountReference() {
        String sensitiveAccount = "user@example.com";

        logger.onProtectionDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), sensitiveAccount,
                "ALLOW", 0, "account-protection-default", "1.0.0",
                Instant.parse("2026-07-22T12:00:00Z")));

        String formatted = appender.list.getFirst().getFormattedMessage();

        assertThat(formatted).doesNotContain(sensitiveAccount);
    }

    @Test
    void logsChallengeCompletionWithStatus() {
        UUID challengeId = UUID.randomUUID();

        logger.onChallengeCompleted(new ChallengeCompleted(
                challengeId, "acct-1", ChallengeType.TOTP_SIMULATED,
                ChallengeStatus.VERIFIED, Instant.parse("2026-07-22T12:00:00Z")));

        String formatted = appender.list.getFirst().getFormattedMessage();

        assertThat(formatted).contains("type=CHALLENGE_COMPLETED");
        assertThat(formatted).contains("final_status=VERIFIED");
        assertThat(formatted).contains("challenge_type=TOTP_SIMULATED");
        assertThat(formatted).contains("challenge_id=" + challengeId);
    }

    @Test
    void logsPolicyActivation() {
        logger.onPolicyActivated(new PolicyActivated(
                "strict-summer-policy", "2.0.0", Instant.parse("2026-07-22T12:00:00Z")));

        String formatted = appender.list.getFirst().getFormattedMessage();

        assertThat(formatted).contains("type=POLICY_ACTIVATED");
        assertThat(formatted).contains("policy=strict-summer-policy:2.0.0");
    }

    @Test
    void logsRecoveryCompletionWithoutAccountReference() {
        String sensitiveAccount = "victim@example.com";
        UUID recoveryId = UUID.randomUUID();

        logger.onRecoveryCompleted(new RecoveryCompleted(
                recoveryId, sensitiveAccount, "COMPLETED",
                Instant.parse("2026-07-22T12:00:00Z")));

        String formatted = appender.list.getFirst().getFormattedMessage();

        assertThat(formatted).contains("type=RECOVERY_COMPLETED");
        assertThat(formatted).contains("recovery_id=" + recoveryId);
        assertThat(formatted).doesNotContain(sensitiveAccount);
    }

    @Test
    void allEventsAtInfoLevel() {
        logger.onProtectionDecisionMade(new ProtectionDecisionMade(
                UUID.randomUUID(), UUID.randomUUID(), "acct",
                "ALLOW", 0, "policy", "1.0", Instant.now()));

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
    }
}
