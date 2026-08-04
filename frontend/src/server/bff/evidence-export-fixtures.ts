import { BffError } from "./foundation";
import type { EvidenceBundle, EvidenceExportService } from "./evidence-export-core";

// Deterministic, permissive demo service (ADR 0015): fixtures mode never calls the backend, so it
// never performs a real signature or hash computation -- it always returns the same fixed
// contentHash/signature pair, kept identical to evidence-verify-fixtures.ts so a bundle exported
// here verifies clean against the fixture verify service without a running backend. Reserved so
// e2e can also exercise the "protection request not found" path without a real database.
export const FIXTURE_NOT_FOUND_PROTECTION_REQUEST_ID = "00000000-0000-4000-b000-000000000002";
export const FIXTURE_CONTENT_HASH = "fx-3c9e1a7b2d4f60815c7e9b1d3f5a7c9e";
export const FIXTURE_SIGNATURE = "fx-sig-9a7c1e5b3d9f7a1c3e5b7d9f1a3c5e7b";
export const FIXTURE_DECISION_ID = "00000000-0000-4000-a000-0000000000f1";
const FIXTURE_GENERATED_AT = "2026-08-01T09:00:00Z";

export const fixtureEvidenceExportService: EvidenceExportService = {
  async export(input): Promise<EvidenceBundle> {
    if (input.protectionRequestId === FIXTURE_NOT_FOUND_PROTECTION_REQUEST_ID) {
      throw new BffError("NOT_FOUND", 404, "The protection request was not found.");
    }
    return {
      manifest: {
        bundleSchemaVersion: "evidence-bundle-1.0",
        decisionId: FIXTURE_DECISION_ID,
        protectionRequestId: input.protectionRequestId,
        generatedAt: FIXTURE_GENERATED_AT,
        exportedBy: "fixture-operator",
        exportReason: input.reason,
        contentHashAlgorithm: "SHA-256",
        contentHash: FIXTURE_CONTENT_HASH,
        signatureAlgorithm: "SHA256withRSA",
        signature: FIXTURE_SIGNATURE,
        signingPublicKey: "fixture-signing-public-key",
      },
      content: {
        bundleSchemaVersion: "evidence-bundle-1.0",
        decisionId: FIXTURE_DECISION_ID,
        protectionRequestId: input.protectionRequestId,
        pseudonymizedAccountReference: "px_fixture0000000000000000000000",
        requestFingerprint: "fixture-request-fingerprint",
        algorithmVersion: "fixture-algorithm-1",
        policyKey: "fixture-login-policy",
        policyVersion: "v1",
        outcome: "ALLOW",
        riskScore: 12,
        normalizedContext: { deviceTrust: "TRUSTED", ipReputation: "CLEAN" },
        decidedAt: FIXTURE_GENERATED_AT,
        reasons: [{ code: "DEVICE_TRUSTED", contribution: -10, ordinal: 0 }],
        replay: {
          matches: true,
          originalOutcome: "ALLOW",
          replayedOutcome: "ALLOW",
          originalRiskScore: 12,
          replayedRiskScore: 12,
        },
        chainProof: {
          chainSequence: 42,
          previousHash: "fx-previous-hash",
          recordHash: "fx-record-hash",
          hashAlgorithm: "SHA-256",
          canonicalSchemaVersion: "v1",
        },
      },
    };
  },
};
