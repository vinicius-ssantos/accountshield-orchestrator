import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldEvidenceVerifyClient,
  parseEvidenceVerifyInput,
  parseEvidenceVerifyResult,
} from "./evidence-verify-core";

const BUNDLE = { manifest: { contentHash: "abc" }, content: { decisionId: "d1" } };

describe("parseEvidenceVerifyInput", () => {
  it("accepts a bundle-shaped body", () => {
    expect(parseEvidenceVerifyInput(BUNDLE)).toEqual(BUNDLE);
  });

  it("rejects a body missing manifest", () => {
    expect(() => parseEvidenceVerifyInput({ content: {} })).toThrow(BffError);
  });

  it("rejects a body missing content", () => {
    expect(() => parseEvidenceVerifyInput({ manifest: {} })).toThrow(BffError);
  });
});

describe("parseEvidenceVerifyResult", () => {
  it("parses a valid result", () => {
    expect(parseEvidenceVerifyResult({ valid: true, problems: [] })).toEqual({ valid: true, problems: [] });
  });

  it("parses an invalid result with problems", () => {
    expect(parseEvidenceVerifyResult({ valid: false, problems: ["signature mismatch"] })).toEqual({
      valid: false,
      problems: ["signature mismatch"],
    });
  });

  it("rejects a malformed result", () => {
    expect(() => parseEvidenceVerifyResult({ valid: "yes" })).toThrow(BffError);
  });
});

describe("AccountShieldEvidenceVerifyClient", () => {
  const client = new AccountShieldEvidenceVerifyClient({
    origin: "http://localhost:8080",
    operatorToken: "operator-token",
    timeoutMs: 1000,
    maxResponseBytes: 128 * 1024,
    maxRequestBytes: 256 * 1024,
  });

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts the bundle to the verify endpoint and returns the parsed result", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ valid: true, problems: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.verify(BUNDLE, "corr-1");

    expect(result).toEqual({ valid: true, problems: [] });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/v1/evidence/verify");
    expect(init.method).toBe("POST");
    expect(init.headers.authorization).toBe("Bearer operator-token");
    expect(JSON.parse(init.body)).toEqual(BUNDLE);
  });

  it("maps a 401 upstream failure to UNAUTHORIZED", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 401 }));

    await expect(client.verify(BUNDLE, "corr-1")).rejects.toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  it("maps a 403 upstream failure to FORBIDDEN", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 403 }));

    await expect(client.verify(BUNDLE, "corr-1")).rejects.toMatchObject({ code: "FORBIDDEN", status: 403 });
  });

  it("rejects an oversized request body before sending it", async () => {
    const oversizedClient = new AccountShieldEvidenceVerifyClient({
      origin: "http://localhost:8080",
      operatorToken: "operator-token",
      timeoutMs: 1000,
      maxResponseBytes: 128 * 1024,
      maxRequestBytes: 8,
    });

    await expect(oversizedClient.verify(BUNDLE, "corr-1")).rejects.toMatchObject({
      code: "PAYLOAD_TOO_LARGE",
      status: 413,
    });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("never leaks the backend token in a thrown error message", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError("network error"));

    try {
      await client.verify(BUNDLE, "corr-1");
      expect.unreachable();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      expect(message).not.toContain("operator-token");
    }
  });
});
