package io.github.viniciusssantos.accountshield.protection;

import java.util.Objects;

public record ClientId(String value) {

    public static final ClientId DEFAULT = new ClientId("default-client");

    public ClientId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("value must contain between 1 and 100 characters");
        }
    }
}
