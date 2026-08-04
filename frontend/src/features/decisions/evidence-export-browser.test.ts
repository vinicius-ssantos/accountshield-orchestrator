import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  EvidenceExportBrowserError,
  exportEvidenceThroughBff,
  verifyEvidenceBundleThroughBff,
} from "./evidence-export-browser";

const PROTECTION_REQUEST_ID = "00000000-0000-4000-b000-000000000007";
const BUNDLE = {
  manifest: {
    bundleSchemaVersion: "evidence-bundle-1.0",
    decisionId: "00000000-0000-4000-a000-0000000000f1",
    protectionRequestId: PROTECTION_REQUEST_ID,
    generatedAt: "2026-08-01T09:00:00Z",
    exportedBy: "operator-1",
    exportReason: "review",
    contentHashAlgorithm: "SHA-256",
    contentHash: "abc123",
    signatureAlgorithm: "SHA256withRSA",
    signature: "sig-abc",
    signingPublicKey: "pubkey-abc",
  },
  content: { decisionId: "00000000-0000-4000-a000-0000000000f1" },
};

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "as_csrf=; Max-Age=0; Path=/";
});

describe("exportEvidenceThroughBff", () => {
  it("echoes the CSRF cookie as a header and resolves with the parsed bundle", async () => {
    document.cookie = "as_csrf=csrf-token-value";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify(BUNDLE), { status: 200 }),
    );

    const result = await exportEvidenceThroughBff(PROTECTION_REQUEST_ID, "review");

    expect(result).toEqual(BUNDLE);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/evidence-export");
    expect(init.headers["x-as-csrf-token"]).toBe("csrf-token-value");
    expect(init.credentials).toBe("same-origin");
    expect(JSON.parse(init.body)).toEqual({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" });
  });

  it("throws EvidenceExportBrowserError with the upstream code on failure", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "NOT_FOUND" }), {
        status: 404,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    const rejection = exportEvidenceThroughBff(PROTECTION_REQUEST_ID, "review");
    await expect(rejection).rejects.toBeInstanceOf(EvidenceExportBrowserError);
    await expect(rejection).rejects.toMatchObject({ code: "NOT_FOUND", status: 404 });
  });
});

describe("verifyEvidenceBundleThroughBff", () => {
  it("does not attach a CSRF header (side-effect-free read)", async () => {
    document.cookie = "as_csrf=csrf-token-value";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ valid: true, problems: [] }), { status: 200 }),
    );

    const result = await verifyEvidenceBundleThroughBff(BUNDLE);

    expect(result).toEqual({ valid: true, problems: [] });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/evidence-verify");
    expect(init.headers["x-as-csrf-token"]).toBeUndefined();
    expect(JSON.parse(init.body)).toEqual(BUNDLE);
  });

  it("throws EvidenceExportBrowserError when verification is not permitted", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "FORBIDDEN" }), {
        status: 403,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(verifyEvidenceBundleThroughBff(BUNDLE)).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
    });
  });
});
