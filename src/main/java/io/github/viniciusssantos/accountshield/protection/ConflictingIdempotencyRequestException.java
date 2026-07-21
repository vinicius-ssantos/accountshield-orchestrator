package io.github.viniciusssantos.accountshield.protection;

import java.util.Objects;

public class ConflictingIdempotencyRequestException extends RuntimeException {

    private final String idempotencyKey;

    public ConflictingIdempotencyRequestException(String idempotencyKey) {
        super("A previous request with the same idempotency key produced a different result");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
