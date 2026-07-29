import { describe, expect, it, vi } from "vitest";

import {
  DecisionSearchBrowserError,
  searchDecisionsThroughBff,
} from "./decision-search-browser";

const PAGE = {
  decisions: [
    {
      decisionReference: "dec_fixture_01",
      correlationId: "corr_demo_login_8f12",
      eventType: "LOGIN_ATTEMPT",
      riskScore: 82,
      riskBand: "HIGH",
      outcome: "START_RECOVERY",
      policyKey: "account-protection",
      policyVersion: "v7",
      decidedAt: "2026-07-29T04:38:00.000Z",
      degraded: false,
      simulated: false,
      provenanceAvailable: true,
    },
  ],
  pageSize: 25,
  hasMore: false,
  source: "fixtures",
  partial: false,
};

describe("browser decision-search transport", () => {
  it("posts filters in a same-origin body instead of the URL", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(PAGE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await searchDecisionsThroughBff(
      { correlationId: "corr_demo_login_8f12", pageSize: 25 },
      { fetchImplementation },
    );

    expect(result.decisions).toHaveLength(1);
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/decision-search");
    expect(url).not.toContain("corr_demo_login_8f12");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({
      correlationId: "corr_demo_login_8f12",
      pageSize: 25,
    });
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
  });

  it("keeps arbitrary problem details out of browser errors", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({
          code: "FORBIDDEN",
          detail: "raw backend role and account detail",
          stack: "sensitive stack",
          retryable: false,
        }),
        {
          status: 403,
          headers: { "content-type": "application/problem+json" },
        },
      ),
    );

    const promise = searchDecisionsThroughBff(
      { pageSize: 25 },
      { fetchImplementation },
    );

    await expect(promise).rejects.toBeInstanceOf(DecisionSearchBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      retryable: false,
      message: "Decision search failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ decisions: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(
      searchDecisionsThroughBff({ pageSize: 25 }, { fetchImplementation }),
    ).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
