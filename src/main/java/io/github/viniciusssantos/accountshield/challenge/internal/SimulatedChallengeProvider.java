package io.github.viniciusssantos.accountshield.challenge.internal;

import io.github.viniciusssantos.accountshield.challenge.ChallengeProvider;
import java.util.HexFormat;
import java.util.Objects;
import java.util.random.RandomGenerator;

final class SimulatedChallengeProvider implements ChallengeProvider {

    private static final int CODE_LENGTH = 6;

    private final RandomGenerator random;

    SimulatedChallengeProvider(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String generateCode() {
        int code = random.nextInt(0, (int) Math.pow(10, CODE_LENGTH));
        return "%0" + CODE_LENGTH + "d".formatted(code);
    }

    @Override
    public boolean verifyCode(String providedCode, String expectedCode) {
        return providedCode.equals(expectedCode);
    }
}
