import { describe, expect, it } from "vitest";

import { BffError } from "./foundation";
import {
  FIXTURE_CONTENT_HASH,
  FIXTURE_NOT_FOUND_PROTECTION_REQUEST_ID,
  FIXTURE_SIGNATURE,
  fixtureEvidenceExportService,
} from "./evidence-export-fixtures";

const PROTECTION_REQUEST_ID = "00000000-0000-4000-b000-000000000007";

describe("fixtureEvidenceExportService", () => {
  it("returns a deterministic bundle for any other protectionRequestId", async () => {
    const bundle = await fixtureEvidenceExportService.export(
      { protectionRequestId: PROTECTION_REQUEST_ID, reason: "customer dispute review" },
      "corr-1",
    );

    expect(bundle.manifest.protectionRequestId).toBe(PROTECTION_REQUEST_ID);
    expect(bundle.manifest.exportReason).toBe("customer dispute review");
    expect(bundle.manifest.contentHash).toBe(FIXTURE_CONTENT_HASH);
    expect(bundle.manifest.signature).toBe(FIXTURE_SIGNATURE);
    expect(bundle.content.protectionRequestId).toBe(PROTECTION_REQUEST_ID);
  });

  it("throws NOT_FOUND for the reserved not-found protection request id", async () => {
    await expect(
      fixtureEvidenceExportService.export(
        { protectionRequestId: FIXTURE_NOT_FOUND_PROTECTION_REQUEST_ID, reason: "review" },
        "corr-1",
      ),
    ).rejects.toMatchObject(new BffError("NOT_FOUND", 404, "The protection request was not found."));
  });
});
