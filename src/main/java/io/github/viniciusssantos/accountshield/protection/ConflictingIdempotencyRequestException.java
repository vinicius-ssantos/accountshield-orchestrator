package io.github.viniciusssantos.accountshield.protection;

import java.util.Objects;

public class ConflictingIdempotencyRequestException extends RuntimeException {

    private final String idempotencyKey;

    public ConflictingIdempotencyRequestException(String idempotencyKey) {
        this(idempotencyKey, null);
    }

    public ConflictingIdempotencyRequestException(String idempotencyKey, Throwable cause) {
        super("A previous request with the same idempotency key produced a different result", cause);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
