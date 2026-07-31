import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import { AccountShieldPolicyDirectoryClient, parsePolicyDirectoryResponse } from "./policy-directory-core";

const RESPONSE = {
  policies: [
    {
      policyKey: "account-protection-default",
      totalVersions: 2,
      activeVersion: "1.0.0",
      activeVersionActivatedAt: "2026-06-02T09:00:00.000Z",
      hasActiveRollout: false,
    },
  ],
};

describe("policy directory BFF adapter", () => {
  it("uses the generated POST operation with an empty body and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldPolicyDirectoryClient({
      origin: "https://accountshield.internal",
      operatorToken: "server-only-token",
      timeoutMs: 1_000,
      maxResponseBytes: 32_768,
      fetchImplementation,
    });

    const result = await client.search("bff_correlation_01");

    expect(result.source).toBe("live");
    expect(result.policies).toHaveLength(1);
    expect(result.policies[0]).toMatchObject({ policyKey: "account-protection-default" });
    expect(fetchImplementation).toHaveBeenCalledOnce();
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe("https://accountshield.internal/api/v1/operator/policies/search");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
      "x-correlation-id": "bff_correlation_01",
    });
    expect(JSON.parse(String(init.body))).toEqual({});
  });

  it("rejects malformed success responses", () => {
    expect(() => parsePolicyDirectoryResponse({ policies: "not-an-array" })).toThrowError(BffError);
    expect(() => parsePolicyDirectoryResponse({ policies: [{ policyKey: "x" }] })).toThrowError(BffError);
  });

  it("maps backend denial without exposing the backend response body", async () => {
    const client = new AccountShieldPolicyDirectoryClient({
      origin: "https://accountshield.internal",
      operatorToken: "server-only-token",
      timeoutMs: 1_000,
      maxResponseBytes: 32_768,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ detail: "internal authorization graph" }), {
          status: 403,
          headers: { "content-type": "application/problem+json" },
        }),
      ),
    });

    await expect(client.search("bff_correlation_02")).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      message: "Operator access is not permitted.",
    });
  });
});
