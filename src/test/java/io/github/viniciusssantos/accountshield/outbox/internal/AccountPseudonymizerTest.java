package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountPseudonymizerTest {

    private final AccountPseudonymizer pseudonymizer = new AccountPseudonymizer("test-secret");

    @Test
    void pseudonymIsDeterministicForTheSameAccount() {
        assertThat(pseudonymizer.pseudonymize("acct-1")).isEqualTo(pseudonymizer.pseudonymize("acct-1"));
    }

    @Test
    void differentAccountsProduceDifferentPseudonyms() {
        assertThat(pseudonymizer.pseudonymize("acct-1")).isNotEqualTo(pseudonymizer.pseudonymize("acct-2"));
    }

    @Test
    void neverReturnsTheRawAccountReference() {
        assertThat(pseudonymizer.pseudonymize("acct-1")).doesNotContain("acct-1");
    }

    @Test
    void producesFixedLengthHexOutput() {
        String token = pseudonymizer.pseudonymize("acct-1");
        assertThat(token).hasSize(64);
        assertThat(token).matches("[0-9a-f]+");
    }
}
