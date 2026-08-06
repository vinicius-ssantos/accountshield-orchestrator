import { describe, expect, it, vi } from "vitest";

import {
  RecoverySearchBrowserError,
  searchRecoveriesThroughBff,
} from "./recovery-search-browser";

const PAGE = {
  recoveries: [
    {
      recoveryReference: "recovery_fixture_01",
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
  pageSize: 25,
  hasMore: false,
  source: "fixtures",
  partial: false,
};

describe("browser recovery-search transport", () => {
  it("posts filters in a same-origin body instead of the URL", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(PAGE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await searchRecoveriesThroughBff(
      { status: "INITIATED", pageSize: 25 },
      { fetchImplementation },
    );

    expect(result.recoveries).toHaveLength(1);
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/recovery-search");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({
      status: "INITIATED",
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

    const promise = searchRecoveriesThroughBff(
      { pageSize: 25 },
      { fetchImplementation },
    );

    await expect(promise).rejects.toBeInstanceOf(RecoverySearchBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      retryable: false,
      message: "Recovery search failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ recoveries: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(
      searchRecoveriesThroughBff({ pageSize: 25 }, { fetchImplementation }),
    ).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
