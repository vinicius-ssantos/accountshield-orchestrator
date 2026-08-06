package io.github.viniciusssantos.accountshield.protection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameInputsProduceTheSameHash() {
        String first = RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, true, false, false, "LOW");
        String second = RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, true, false, false, "LOW");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void changingAnyFieldChangesTheHash() {
        String base = RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, true, false, false, "LOW");

        assertThat(RequestFingerprint.compute(
                "other-client", "acct-1", "LOGIN_ATTEMPT", 2, true, false, false, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-2", "LOGIN_ATTEMPT", 2, true, false, false, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-1", "SENSITIVE_ACTION", 2, true, false, false, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 3, true, false, false, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, false, false, false, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, true, true, false, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, true, false, true, "LOW"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.compute(
                "default-client", "acct-1", "LOGIN_ATTEMPT", 2, true, false, false, "HIGH"))
                .isNotEqualTo(base);
    }
}
