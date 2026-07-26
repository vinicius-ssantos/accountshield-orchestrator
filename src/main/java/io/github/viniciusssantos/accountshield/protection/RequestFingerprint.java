package io.github.viniciusssantos.accountshield.protection;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The canonical, deterministic hash of the fields that define a protection decision request.
 * Shared between {@code ProtectionDecisionApplicationService} (computed at decision time) and
 * replay (recomputed from reconstructed historical input) so both sides can never drift —
 * there is exactly one implementation of this hash, not two that happen to agree today.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String compute(
            String clientId,
            String accountReference,
            String eventType,
            int failedAttempts,
            boolean newDevice,
            boolean impossibleTravel,
            boolean compromisedCredential,
            String networkRiskLevel) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(clientId);
                output.writeUTF(accountReference);
                output.writeUTF(eventType);
                output.writeInt(failedAttempts);
                output.writeBoolean(newDevice);
                output.writeBoolean(impossibleTravel);
                output.writeBoolean(compromisedCredential);
                output.writeUTF(networkRiskLevel);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("failed to compute request fingerprint", exception);
        }
    }
}
