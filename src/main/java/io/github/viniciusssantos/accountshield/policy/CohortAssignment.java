package io.github.viniciusssantos.accountshield.policy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministically assigns a subject to one of 100 buckets for a given policy key. The bucket
 * is fixed regardless of rollout percentage, so raising the percentage only ever adds subjects to
 * the candidate cohort -- it never reshuffles subjects already selected out of it.
 */
public final class CohortAssignment {

    private static final int BUCKET_COUNT = 100;

    private CohortAssignment() {
    }

    public static int bucket(String clientId, String subject, String policyKey) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(clientId);
                output.writeUTF(subject);
                output.writeUTF(policyKey);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes.toByteArray());
            int unsignedInt = ((hash[0] & 0xFF) << 24)
                    | ((hash[1] & 0xFF) << 16)
                    | ((hash[2] & 0xFF) << 8)
                    | (hash[3] & 0xFF);
            return Math.floorMod(unsignedInt, BUCKET_COUNT);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("failed to compute cohort bucket", exception);
        }
    }

    public static boolean inCandidateCohort(String clientId, String subject, String policyKey, int rolloutPercentage) {
        return bucket(clientId, subject, policyKey) < rolloutPercentage;
    }
}
