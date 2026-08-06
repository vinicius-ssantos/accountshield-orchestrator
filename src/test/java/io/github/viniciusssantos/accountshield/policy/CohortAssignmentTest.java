package io.github.viniciusssantos.accountshield.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CohortAssignmentTest {

    @Test
    void sameInputsProduceTheSameBucket() {
        int first = CohortAssignment.bucket("client-1", "acct-1", "account-protection-default");
        int second = CohortAssignment.bucket("client-1", "acct-1", "account-protection-default");

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 99);
    }

    @Test
    void differentSubjectsTendToProduceDifferentBuckets() {
        int bucketA = CohortAssignment.bucket("client-1", "acct-1", "account-protection-default");
        int bucketB = CohortAssignment.bucket("client-1", "acct-2", "account-protection-default");
        int bucketC = CohortAssignment.bucket("client-2", "acct-1", "account-protection-default");
        int bucketD = CohortAssignment.bucket("client-1", "acct-1", "other-policy-key");

        assertThat(java.util.Set.of(bucketA, bucketB, bucketC, bucketD).size()).isGreaterThan(1);
    }

    @Test
    void raisingPercentageOnlyExpandsTheCandidateCohortNeverContractsIt() {
        for (int i = 0; i < 500; i++) {
            String subject = "acct-" + UUID.randomUUID();
            int bucket = CohortAssignment.bucket("client-1", subject, "account-protection-default");

            boolean inAt10 = CohortAssignment.inCandidateCohort("client-1", subject, "account-protection-default", 10);
            boolean inAt50 = CohortAssignment.inCandidateCohort("client-1", subject, "account-protection-default", 50);
            boolean inAt100 = CohortAssignment.inCandidateCohort("client-1", subject, "account-protection-default", 100);

            assertThat(inAt10).isEqualTo(bucket < 10);
            if (inAt10) {
                assertThat(inAt50).isTrue();
            }
            if (inAt50) {
                assertThat(inAt100).isTrue();
            }
        }
    }

    @Test
    void zeroPercentExcludesEveryoneAndHundredPercentIncludesEveryone() {
        for (int i = 0; i < 50; i++) {
            String subject = "acct-" + UUID.randomUUID();
            assertThat(CohortAssignment.inCandidateCohort("client-1", subject, "account-protection-default", 0))
                    .isFalse();
            assertThat(CohortAssignment.inCandidateCohort("client-1", subject, "account-protection-default", 100))
                    .isTrue();
        }
    }
}
