package io.github.viniciusssantos.accountshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.viniciusssantos.accountshield.challenge.ChallengeIssued;
import io.github.viniciusssantos.accountshield.challenge.ChallengePurpose;
import io.github.viniciusssantos.accountshield.challenge.ChallengeService;
import io.github.viniciusssantos.accountshield.challenge.ChallengeVerificationCommand;
import io.github.viniciusssantos.accountshield.policy.CreatePolicyCommand;
import io.github.viniciusssantos.accountshield.policy.IllegalPolicyTransitionException;
import io.github.viniciusssantos.accountshield.policy.PolicyLifecycleService;
import io.github.viniciusssantos.accountshield.policy.PolicyStatus;
import io.github.viniciusssantos.accountshield.policy.PolicyVersionSummary;
import io.github.viniciusssantos.accountshield.policy.internal.persistence.PolicyVersionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
@RecordApplicationEvents
class PolicyLifecycleIntegrationTest {

    private static final String ACTOR = "policy-admin-integration-test";

    @Autowired
    private PolicyLifecycleService lifecycleService;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private PolicyVersionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

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

        PolicyVersionSummary activated = activate(key, version);
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

        assertThatThrownBy(() -> activate(key, "1.0.0"))
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
        PolicyVersionSummary result = retire(key, "1.0.0");

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
        retire(key, "1.0.0");

        assertThatThrownBy(() -> lifecycleService.validate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> activate(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
        assertThatThrownBy(() -> lifecycleService.reject(key, "1.0.0"))
                .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    private PolicyVersionSummary createAndActivate(String key, String version, short allow, short stepUp) {
        lifecycleService.createDraft(new CreatePolicyCommand(key, version, allow, stepUp));
        lifecycleService.validate(key, version);
        return activate(key, version);
    }

    private PolicyVersionSummary activate(String key, String version) {
        UUID challengeId = verifiedStepUp(lifecycleService.requestActivationStepUp(key, version, ACTOR));
        return lifecycleService.activate(key, version, challengeId, ACTOR);
    }

    private PolicyVersionSummary retire(String key, String version) {
        UUID challengeId = verifiedStepUp(lifecycleService.requestRetirementStepUp(key, version, ACTOR));
        return lifecycleService.retire(key, version, challengeId, ACTOR);
    }

    private UUID verifiedStepUp(UUID challengeId) {
        String issuedCode = events.stream(ChallengeIssued.class)
                .filter(event -> event.challengeId().equals(challengeId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .issuedCode();
        challengeService.verify(new ChallengeVerificationCommand(
                challengeId, issuedCode, ChallengePurpose.PRIVILEGED_OPERATION,
                lookUpContextId(challengeId)));
        return challengeId;
    }

    private UUID lookUpContextId(UUID challengeId) {
        repository.flush();
        return jdbcTemplate.queryForObject(
                "SELECT context_id FROM challenge.challenge_plan WHERE id = ?",
                UUID.class,
                challengeId);
    }
}
