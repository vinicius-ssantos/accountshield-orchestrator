import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldDecisionSearchClient,
  parseDecisionSearchInput,
} from "./decision-search-core";

const RESPONSE = {
  decisions: [
    {
      decisionReference: "decision_ref_01",
      correlationId: "corr_live_01",
      eventType: "LOGIN_ATTEMPT",
      outcome: "REQUIRE_STEP_UP",
      riskScore: 48,
      riskBand: "MEDIUM",
      policyKey: "account-protection",
      policyVersion: "v7",
      decidedAt: "2026-07-29T12:00:00.000Z",
      degraded: false,
      simulated: false,
      provenanceAvailable: true,
    },
  ],
  nextCursor: null,
  pageSize: 25,
  hasMore: false,
};

describe("decision search BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldDecisionSearchClient({
      origin: "https://accountshield.internal",
      operatorToken: "server-only-token",
      timeoutMs: 1_000,
      maxResponseBytes: 32_768,
      fetchImplementation,
    });

    const result = await client.search(
      { correlationId: "corr_live_01", pageSize: 25 },
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
      "https://accountshield.internal/api/v1/operator/decisions/search",
    );
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
      "x-correlation-id": "bff_correlation_01",
    });
    expect(JSON.parse(String(init.body))).toEqual({
      correlationId: "corr_live_01",
      pageSize: 25,
    });
  });

  it("rejects unsupported filters before any backend request", () => {
    expect(() =>
      parseDecisionSearchInput({
        correlationId: "contains spaces",
        pageSize: 25,
      }),
    ).toThrowError(BffError);
  });

  it("maps backend denial without exposing the backend response body", async () => {
    const client = new AccountShieldDecisionSearchClient({
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
    const client = new AccountShieldDecisionSearchClient({
      origin: "https://accountshield.internal",
      operatorToken: "server-only-token",
      timeoutMs: 1_000,
      maxResponseBytes: 32_768,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ decisions: "not-an-array" }), {
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
