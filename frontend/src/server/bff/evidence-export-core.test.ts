import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldEvidenceExportClient,
  parseEvidenceExportInput,
  parseEvidenceBundle,
} from "./evidence-export-core";

const PROTECTION_REQUEST_ID = "00000000-0000-4000-b000-000000000007";

const MANIFEST = {
  bundleSchemaVersion: "evidence-bundle-1.0",
  decisionId: "00000000-0000-4000-a000-0000000000f1",
  protectionRequestId: PROTECTION_REQUEST_ID,
  generatedAt: "2026-08-01T09:00:00Z",
  exportedBy: "operator-1",
  exportReason: "customer dispute review",
  contentHashAlgorithm: "SHA-256",
  contentHash: "abc123",
  signatureAlgorithm: "SHA256withRSA",
  signature: "sig-abc",
  signingPublicKey: "pubkey-abc",
};

const BUNDLE = { manifest: MANIFEST, content: { decisionId: MANIFEST.decisionId } };

describe("parseEvidenceExportInput", () => {
  it("accepts a valid protectionRequestId and reason", () => {
    expect(parseEvidenceExportInput({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" })).toEqual({
      protectionRequestId: PROTECTION_REQUEST_ID,
      reason: "review",
    });
  });

  it("rejects a non-UUID protectionRequestId", () => {
    expect(() => parseEvidenceExportInput({ protectionRequestId: "nope", reason: "review" })).toThrow(BffError);
  });

  it("rejects a blank reason", () => {
    expect(() => parseEvidenceExportInput({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "  " })).toThrow(
      BffError,
    );
  });

  it("rejects a reason longer than 500 characters", () => {
    expect(() =>
      parseEvidenceExportInput({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "x".repeat(501) }),
    ).toThrow(BffError);
  });
});

describe("parseEvidenceBundle", () => {
  it("parses a well-formed bundle", () => {
    expect(parseEvidenceBundle(BUNDLE)).toEqual(BUNDLE);
  });

  it("rejects a bundle missing content", () => {
    expect(() => parseEvidenceBundle({ manifest: MANIFEST })).toThrow(BffError);
  });

  it("rejects a manifest missing a required field", () => {
    const { signature, ...withoutSignature } = MANIFEST;
    void signature;
    expect(() => parseEvidenceBundle({ manifest: withoutSignature, content: {} })).toThrow(BffError);
  });
});

describe("AccountShieldEvidenceExportClient", () => {
  const client = new AccountShieldEvidenceExportClient({
    origin: "http://localhost:8080",
    operatorToken: "operator-token",
    timeoutMs: 1000,
    maxResponseBytes: 128 * 1024,
  });

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts to the export endpoint and returns the parsed bundle", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify(BUNDLE), { status: 200, headers: { "content-type": "application/json" } }),
    );

    const result = await client.export({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, "corr-1");

    expect(result).toEqual(BUNDLE);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/v1/evidence/export");
    expect(init.method).toBe("POST");
    expect(init.headers.authorization).toBe("Bearer operator-token");
    expect(JSON.parse(init.body)).toEqual({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" });
  });

  it("maps a bodyless 404 to NOT_FOUND", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 404 }));

    await expect(
      client.export({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, "corr-1"),
    ).rejects.toMatchObject({ code: "NOT_FOUND", status: 404 });
  });

  it("maps a 400 EVIDENCE_INVALID_REQUEST upstream problem to INVALID_REQUEST", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "EVIDENCE_INVALID_REQUEST" }), {
        status: 400,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(
      client.export({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, "corr-1"),
    ).rejects.toMatchObject({ code: "INVALID_REQUEST", status: 400 });
  });

  it("maps a 401 upstream failure to UNAUTHORIZED", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 401 }));

    await expect(
      client.export({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, "corr-1"),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("maps a 403 upstream failure to FORBIDDEN", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 403 }));

    await expect(
      client.export({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, "corr-1"),
    ).rejects.toMatchObject({ code: "FORBIDDEN", status: 403 });
  });

  it("never leaks the backend token in a thrown error message", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError("network error"));

    try {
      await client.export({ protectionRequestId: PROTECTION_REQUEST_ID, reason: "review" }, "corr-1");
      expect.unreachable();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      expect(message).not.toContain("operator-token");
    }
  });
});
