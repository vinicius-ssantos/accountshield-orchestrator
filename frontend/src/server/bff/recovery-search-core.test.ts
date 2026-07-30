import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldRecoverySearchClient,
  parseRecoverySearchInput,
} from "./recovery-search-core";

const RESPONSE = {
  recoveries: [
    {
      recoveryReference: "recovery_ref_01",
      maskedSubjectReference: "••••7f21",
      eventType: "LOGIN",
      status: "INITIATED",
      terminal: false,
      classification: "IMMEDIATE",
      classificationRuleVersion: "recovery-classification-1.0",
      riskScore: 12,
      initiatedAt: "2026-07-30T09:12:00.000Z",
      updatedAt: "2026-07-30T09:12:00.000Z",
      eligibleAfter: null,
      originatingDecisionReference: "••••a001",
      reviewState: "NOT_APPLICABLE",
      challengeExpected: true,
    },
  ],
  nextCursor: null,
  pageSize: 25,
  hasMore: false,
};

describe("recovery search BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldRecoverySearchClient({
      origin: "https://accountshield.internal",
      operatorToken: "server-only-token",
      timeoutMs: 1_000,
      maxResponseBytes: 32_768,
      fetchImplementation,
    });

    const result = await client.search(
      { status: "INITIATED", pageSize: 25 },
      "bff_correlation_01",
    );

    expect(result).toMatchObject({
      source: "live",
      partial: false,
      pageSize: 25,
      hasMore: false,
    });
    expect(fetchImplementation).toHaveBeenCalledOnce();
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe(
      "https://accountshield.internal/api/v1/operator/recoveries/search",
    );
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
      "x-correlation-id": "bff_correlation_01",
    });
    expect(JSON.parse(String(init.body))).toEqual({
      status: "INITIATED",
      pageSize: 25,
    });
  });

  it("rejects unsupported filters before any backend request", () => {
    expect(() =>
      parseRecoverySearchInput({
        status: "NOT_A_REAL_STATUS",
        pageSize: 25,
      }),
    ).toThrowError(BffError);
  });

  it("rejects an inverted time range before any backend request", () => {
    expect(() =>
      parseRecoverySearchInput({
        initiatedFrom: "2026-07-30T10:00:00.000Z",
        initiatedTo: "2026-07-30T09:00:00.000Z",
        pageSize: 25,
      }),
    ).toThrowError(BffError);
  });

  it("maps backend denial without exposing the backend response body", async () => {
    const client = new AccountShieldRecoverySearchClient({
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

    await expect(client.search({ pageSize: 25 }, "bff_correlation_02")).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      message: "Operator access is not permitted.",
    });
  });

  it("rejects malformed success responses", async () => {
    const client = new AccountShieldRecoverySearchClient({
      origin: "https://accountshield.internal",
      operatorToken: "server-only-token",
      timeoutMs: 1_000,
      maxResponseBytes: 32_768,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ recoveries: "not-an-array" }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    });

    await expect(client.search({ pageSize: 25 }, "bff_correlation_03")).rejects.toMatchObject({
      code: "UPSTREAM_MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
