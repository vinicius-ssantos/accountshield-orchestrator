package io.github.viniciusssantos.accountshield.crypto.internal.persistence;

import java.time.Instant;

public record SubjectKeyRecord(
        String subjectId,
        byte[] wrappedDek,
        byte[] dekNonce,
        Integer kekVersion,
        Instant createdAt,
        Instant rewrappedAt,
        Instant destroyedAt) {

    public boolean destroyed() {
        return destroyedAt != null;
    }
}
