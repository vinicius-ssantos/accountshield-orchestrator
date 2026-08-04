import { describe, expect, it } from "vitest";

import { FIXTURE_CONTENT_HASH, FIXTURE_SIGNATURE } from "./evidence-export-fixtures";
import { fixtureEvidenceVerifyService } from "./evidence-verify-fixtures";

describe("fixtureEvidenceVerifyService", () => {
  it("reports an unmodified fixture bundle as valid", async () => {
    const result = await fixtureEvidenceVerifyService.verify(
      { manifest: { contentHash: FIXTURE_CONTENT_HASH, signature: FIXTURE_SIGNATURE }, content: {} },
      "corr-1",
    );
    expect(result).toEqual({ valid: true, problems: [] });
  });

  it("reports a tampered content hash as invalid with an explainable problem", async () => {
    const result = await fixtureEvidenceVerifyService.verify(
      { manifest: { contentHash: "tampered", signature: FIXTURE_SIGNATURE }, content: {} },
      "corr-1",
    );
    expect(result.valid).toBe(false);
    expect(result.problems).toContain("content_hash does not match the recomputed canonical content");
  });

  it("reports a tampered signature as invalid with an explainable problem", async () => {
    const result = await fixtureEvidenceVerifyService.verify(
      { manifest: { contentHash: FIXTURE_CONTENT_HASH, signature: "tampered" }, content: {} },
      "corr-1",
    );
    expect(result.valid).toBe(false);
    expect(result.problems).toContain("signature does not verify against the manifest's embedded public key");
  });

  it("reports both problems when the manifest is missing entirely", async () => {
    const result = await fixtureEvidenceVerifyService.verify({ content: {} }, "corr-1");
    expect(result.valid).toBe(false);
    expect(result.problems).toHaveLength(2);
  });
});
