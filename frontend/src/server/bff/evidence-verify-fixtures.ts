import { FIXTURE_CONTENT_HASH, FIXTURE_SIGNATURE } from "./evidence-export-fixtures";
import type { EvidenceVerifyService } from "./evidence-verify-core";

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

// Mirrors the real backend's two independent checks (recomputed content hash, signature) closely
// enough to demonstrate tamper detection without a real signer: it compares the submitted
// manifest's contentHash/signature against the fixed values evidence-export-fixtures.ts always
// produces, so re-verifying an unmodified fixture bundle passes and a hand-edited one fails with
// the same problem vocabulary the backend uses.
export const fixtureEvidenceVerifyService: EvidenceVerifyService = {
  async verify(bundle) {
    const manifest = isRecord(bundle.manifest) ? bundle.manifest : {};
    const problems: string[] = [];
    if (manifest.contentHash !== FIXTURE_CONTENT_HASH) {
      problems.push("content_hash does not match the recomputed canonical content");
    }
    if (manifest.signature !== FIXTURE_SIGNATURE) {
      problems.push("signature does not verify against the manifest's embedded public key");
    }
    return { valid: problems.length === 0, problems };
  },
};
