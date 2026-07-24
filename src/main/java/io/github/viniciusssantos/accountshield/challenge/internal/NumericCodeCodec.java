package io.github.viniciusssantos.accountshield.challenge.internal;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
class NumericCodeCodec implements SimulatedChallengeCodec {

    private static final int CODE_LENGTH = 6;
    private static final int BOUND = (int) Math.pow(10, CODE_LENGTH);

    private final SecureRandom random = new SecureRandom();

    @Override
    public String issue() {
        int code = random.nextInt(BOUND);
        return "%06d".formatted(code);
    }
}
