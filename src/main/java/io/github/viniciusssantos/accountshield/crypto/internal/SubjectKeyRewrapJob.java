package io.github.viniciusssantos.accountshield.crypto.internal;

import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyRecord;
import io.github.viniciusssantos.accountshield.crypto.internal.persistence.SubjectKeyStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-encrypts (rewraps) subject data-encryption keys still wrapped by a non-active
 * key-encryption key version, in bounded batches. This is the "re-encrypt historical values"
 * background job required by key rotation -- it never touches the encrypted field ciphertext
 * itself (which is encrypted with the subject's DEK, not the KEK directly), only the much
 * smaller per-subject wrapping, so cost scales with the number of subjects, not the number of
 * rows referencing them.
 */
@Component
public class SubjectKeyRewrapJob {

    private static final Logger log = LoggerFactory.getLogger(SubjectKeyRewrapJob.class);

    private final SubjectKeyStore subjectKeyStore;
    private final KeyEncryptionKeyResolver kekResolver;
    private final Clock clock;
    private final int batchSize;
    private final MeterRegistry meterRegistry;
    private final SecureRandom secureRandom = new SecureRandom();

    public SubjectKeyRewrapJob(
            SubjectKeyStore subjectKeyStore,
            KeyEncryptionKeyResolver kekResolver,
            @Qualifier("decisionClock") Clock clock,
            @Value("${accountshield.crypto.rewrap.batch-size:200}") int batchSize,
            MeterRegistry meterRegistry) {
        this.subjectKeyStore = subjectKeyStore;
        this.kekResolver = kekResolver;
        this.clock = clock;
        this.batchSize = batchSize;
        this.meterRegistry = meterRegistry;
        Gauge.builder("accountshield.crypto.rewrap.pending", subjectKeyStore,
                        store -> store.countNeedingRewrap(kekResolver.activeVersion()))
                .description("Subject keys still wrapped by a non-active key-encryption key version")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${accountshield.crypto.rewrap.fixed-delay:1h}")
    @Transactional
    public void rewrapPendingSubjectKeys() {
        int activeVersion = kekResolver.activeVersion();
        List<SubjectKeyRecord> batch = subjectKeyStore.findBatchNeedingRewrap(activeVersion, batchSize);
        for (SubjectKeyRecord record : batch) {
            byte[] dek = AesGcmCipher.decrypt(
                    kekResolver.keyForVersion(record.kekVersion()), record.dekNonce(), record.wrappedDek());
            byte[] newNonce = AesGcmCipher.randomNonce(secureRandom);
            byte[] newWrappedDek = AesGcmCipher.encrypt(kekResolver.keyForVersion(activeVersion), newNonce, dek);
            subjectKeyStore.rewrap(record.subjectId(), newWrappedDek, newNonce, activeVersion, clock.instant());
        }
        if (!batch.isEmpty()) {
            log.info("crypto_subject_key_rewrap count={} activeKekVersion={}", batch.size(), activeVersion);
        }
        Counter.builder("accountshield.crypto.rewrap.count")
                .description("Total subject keys re-wrapped onto the active key-encryption key version")
                .register(meterRegistry)
                .increment(batch.size());
    }
}
