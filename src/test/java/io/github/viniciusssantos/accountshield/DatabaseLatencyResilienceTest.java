package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionCommand;
import io.github.viniciusssantos.accountshield.protection.ProtectionDecisionService;
import io.github.viniciusssantos.accountshield.protection.ProtectionEventType;
import io.github.viniciusssantos.accountshield.risk.NetworkRiskLevel;
import io.github.viniciusssantos.accountshield.risk.RiskSignalEnvelope;
import io.github.viniciusssantos.accountshield.risk.RiskSignals;
import io.github.viniciusssantos.accountshield.risk.SignalConfidence;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Issue #39: "database latency and connection interruption." A separate Spring context (not the
 * shared {@code PostgreSqlTestConfiguration} every other integration test uses) because this needs
 * a materially different topology: Postgres and Toxiproxy on a shared Docker network, with the
 * app's datasource routed *through* the proxy so a toxic actually affects the real connection the
 * application uses -- not a side channel. Uses the {@code org.testcontainers.containers
 * .ToxiproxyContainer} deprecated-but-still-functional API (its {@code getProxy(...)}
 * convenience method) rather than hand-wiring the newer package's lower-level HTTP client, given
 * the effort already spent standing up this test; see ADR 0032's Alternatives considered.
 *
 * <p>Tagged {@code resilience}: excluded from the default CI gate (real container startup plus a
 * connection-timeout wait makes this slower than this codebase's other tests), included in the
 * nightly full-suite workflow.</p>
 */
@Tag("resilience")
@Testcontainers
@SpringBootTest
class DatabaseLatencyResilienceTest {

    private static final Network network = Network.newNetwork();

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("accountshield")
            .withUsername("accountshield")
            .withPassword("accountshield-test-only")
            .withNetwork(network)
            .withNetworkAliases("postgres");

    @Container
    static ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
            .withNetwork(network);

    private static ToxiproxyContainer.ContainerProxy proxy;

    @DynamicPropertySource
    static void configureDatasourceThroughTheProxy(DynamicPropertyRegistry registry) {
        proxy = toxiproxy.getProxy(postgres, 5432);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://"
                + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort() + "/accountshield");
        registry.add("spring.datasource.username", () -> "accountshield");
        registry.add("spring.datasource.password", () -> "accountshield-test-only");
    }

    @Autowired
    private ProtectionDecisionService protectionDecisionService;

    @AfterEach
    void restoreTheConnection() throws Exception {
        proxy.setConnectionCut(false);
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    @Test
    void aCutConnectionSurfacesAsAControlledFailureRatherThanAnIndefiniteHang() {
        proxy.setConnectionCut(true);

        long start = System.nanoTime();
        assertThatThrownBy(() -> protectionDecisionService.decide(decisionCommand()))
                .isInstanceOf(DataAccessException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // HikariCP's configured connection-timeout (5s) + validation-timeout (3s) bound this --
        // a generous ceiling well under what would indicate an actual indefinite hang.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void injectedLatencyWithinTheConnectionTimeoutStillSucceeds() throws Exception {
        // Well under HikariCP's 5s connection-timeout / 3s validation-timeout (application.yml)
        // -- this proves ordinary network latency does not itself cause failures, only an
        // interruption that exceeds those configured bounds does (the test above).
        proxy.toxics().latency("db-latency", ToxicDirection.DOWNSTREAM, 300);

        var result = protectionDecisionService.decide(decisionCommand());

        assertThat(result).isNotNull();
    }

    @Test
    void aRestoredConnectionRecoversAndSubsequentDecisionsSucceed() {
        proxy.setConnectionCut(true);
        assertThatThrownBy(() -> protectionDecisionService.decide(decisionCommand()))
                .isInstanceOf(DataAccessException.class);

        proxy.setConnectionCut(false);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(protectionDecisionService.decide(decisionCommand())).isNotNull());
    }

    private ProtectionDecisionCommand decisionCommand() {
        return new ProtectionDecisionCommand(
                "acct-latency-" + UUID.randomUUID(),
                ProtectionEventType.LOGIN_ATTEMPT,
                new RiskSignalEnvelope(
                        new RiskSignals(0, false, false, false, NetworkRiskLevel.LOW),
                        "CLIENT_SUPPLIED", Instant.now(), SignalConfidence.HIGH, null, true),
                "idem-" + UUID.randomUUID());
    }
}
