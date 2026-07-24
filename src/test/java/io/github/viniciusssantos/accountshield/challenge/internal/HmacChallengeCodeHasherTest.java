package io.github.viniciusssantos.accountshield.challenge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacChallengeCodeHasherTest {

    private final HmacChallengeCodeHasher hasher = new HmacChallengeCodeHasher("test-secret");

    @Test
    void hashIsDeterministicForTheSameInput() {
        assertThat(hasher.hash("123456")).isEqualTo(hasher.hash("123456"));
    }

    @Test
    void differentCodesProduceDifferentHashes() {
        assertThat(hasher.hash("123456")).isNotEqualTo(hasher.hash("654321"));
    }

    @Test
    void neverStoresOrReturnsTheRawCode() {
        assertThat(hasher.hash("123456")).doesNotContain("123456");
    }

    @Test
    void matchesReturnsTrueForTheCorrectCode() {
        String stored = hasher.hash("123456");
        assertThat(hasher.matches("123456", stored)).isTrue();
    }

    @Test
    void matchesReturnsFalseForTheWrongCode() {
        String stored = hasher.hash("123456");
        assertThat(hasher.matches("000000", stored)).isFalse();
    }
}
