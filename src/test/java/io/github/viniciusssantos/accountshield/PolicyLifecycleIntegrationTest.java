package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionEntity;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PolicyLifecycleIntegrationTest {

    @Autowired
    private PolicyLifecycleService lifecycleService;

    @Autowired
    private PolicyVersionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void createsDraftValidatesAndActivatesPolicy() {
        String key = "test-policy-" + java.util.UUID.randomUUID();
        String version = "1.0.0";

        PolicyVersionSummary draft = lifecycleService.createDraft(
                new CreatePolicyCommand(key, version, (short) 25, (short) 65));
        assertThat(draft.status()).isEqualTo(PolicyStatus.DRAFT);

        PolicyVersionSummary validated = lifecycleService.validate(key, version);
        assertThat(validated.status()).isEqualTo(PolicyStatus.VALIDATED);

        PolicyVersionSummary activated = lifecycleService.activate(key, version);
        assertThat(activated.status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(activated.activatedAt()).isNotNull();

        repository.flush();

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, version);
        assertThat(dbStatus).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    void activatingNewVersionRetiresPreviousActive() {
        String key = "swap-policy-" + java.util.UUID.randomUUID();

        PolicyVersionSummary v1 = createAndActivate(key, "1.0.0", (short) 20, (short) 60);
        PolicyVersionSummary v2 = createAndActivate(key, "2.0.0", (short) 25, (short) 65);

        assertThat(v2.status()).isEqualTo(PolicyStatus.ACTIVE);

        repository.flush();

        String v1Status = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, "1.0.0");
        assertThat(v1Status).isEqualTo("RETIRED");

        String v2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, "2.0.0");
        assertThat(v2Status).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    void cannotActivateDraftDirectly() {
        String key = "skip-policy-" + java.util.UUID.randomUUID();

        lifecycleService.createDraft(
                new CreatePolicyCommand(key, "1.0.0", (short) 25, (short) 65));

        assertThatThrownBy(() -> lifecycleService.activate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    @Transactional
    void rejectTransitionsValidatedToRejected() {
        String key = "reject-policy-" + java.util.UUID.randomUUID();

        lifecycleService.createDraft(
                new CreatePolicyCommand(key, "1.0.0", (short) 25, (short) 65));
        lifecycleService.validate(key, "1.0.0");
        PolicyVersionSummary result = lifecycleService.reject(key, "1.0.0");

        assertThat(result.status()).isEqualTo(PolicyStatus.REJECTED);
    }

    @Test
    @Transactional
    void retireTransitionsActiveToRetired() {
        String key = "retire-policy-" + java.util.UUID.randomUUID();

        createAndActivate(key, "1.0.0", (short) 20, (short) 60);
        PolicyVersionSummary result = lifecycleService.retire(key, "1.0.0");

        assertThat(result.status()).isEqualTo(PolicyStatus.RETIRED);

        repository.flush();

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM policy.policy_version WHERE policy_key = ? AND version = ?",
                String.class, key, "1.0.0");
        assertThat(dbStatus).isEqualTo("RETIRED");
    }

    @Test
    @Transactional
    void cannotTransitionFromTerminalState() {
        String key = "terminal-policy-" + java.util.UUID.randomUUID();

        createAndActivate(key, "1.0.0", (short) 20, (short) 60);
        lifecycleService.retire(key, "1.0.0");

        assertThatThrownBy(() -> lifecycleService.validate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> lifecycleService.activate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> lifecycleService.reject(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    private PolicyVersionSummary createAndActivate(String key, String version, short allow, short stepUp) {
        lifecycleService.createDraft(new CreatePolicyCommand(key, version, allow, stepUp));
        lifecycleService.validate(key, version);
        return lifecycleService.activate(key, version);
    }
}
