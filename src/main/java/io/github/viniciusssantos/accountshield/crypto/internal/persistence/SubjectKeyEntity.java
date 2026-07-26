package io.github.viniciusssantos.accountshield.crypto.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "subject_key", schema = "crypto")
public class SubjectKeyEntity {

    @Id
    @Column(name = "subject_id", length = 64)
    private String subjectId;

    @Column(name = "wrapped_dek")
    private byte[] wrappedDek;

    @Column(name = "dek_nonce")
    private byte[] dekNonce;

    @Column(name = "kek_version")
    private Integer kekVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "rewrapped_at")
    private Instant rewrappedAt;

    @Column(name = "destroyed_at")
    private Instant destroyedAt;

    protected SubjectKeyEntity() {
    }

    public SubjectKeyEntity(
            String subjectId, byte[] wrappedDek, byte[] dekNonce, int kekVersion, Instant createdAt) {
        this.subjectId = subjectId;
        this.wrappedDek = wrappedDek;
        this.dekNonce = dekNonce;
        this.kekVersion = kekVersion;
        this.createdAt = createdAt;
    }

    public String subjectId() {
        return subjectId;
    }

    public byte[] wrappedDek() {
        return wrappedDek;
    }

    public byte[] dekNonce() {
        return dekNonce;
    }

    public Integer kekVersion() {
        return kekVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant rewrappedAt() {
        return rewrappedAt;
    }

    public Instant destroyedAt() {
        return destroyedAt;
    }

    public void rewrap(byte[] newWrappedDek, byte[] newNonce, int newKekVersion, Instant now) {
        this.wrappedDek = newWrappedDek;
        this.dekNonce = newNonce;
        this.kekVersion = newKekVersion;
        this.rewrappedAt = now;
    }

    public void destroy(Instant now) {
        this.wrappedDek = null;
        this.dekNonce = null;
        this.kekVersion = null;
        this.destroyedAt = now;
    }
}
