import { describe, expect, it, vi } from "vitest";

import { PolicyDirectoryBrowserError, searchPoliciesThroughBff } from "./policy-directory-browser";

const PAGE = {
  policies: [
    {
      policyKey: "account-protection-default",
      totalVersions: 2,
      activeVersion: "1.0.0",
      activeVersionActivatedAt: "2026-06-02T09:00:00.000Z",
      hasActiveRollout: false,
    },
  ],
  source: "fixtures",
};

describe("browser policy-directory transport", () => {
  it("posts an empty body to a same-origin endpoint", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(PAGE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await searchPoliciesThroughBff({ fetchImplementation });

    expect(result.policies).toHaveLength(1);
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/policy-directory");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({});
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
  });

  it("keeps arbitrary problem details out of browser errors", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({ code: "FORBIDDEN", detail: "raw backend role detail", retryable: false }),
        { status: 403, headers: { "content-type": "application/problem+json" } },
      ),
    );

    const promise = searchPoliciesThroughBff({ fetchImplementation });

    await expect(promise).rejects.toBeInstanceOf(PolicyDirectoryBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      retryable: false,
      message: "Policy directory search failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ policies: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(searchPoliciesThroughBff({ fetchImplementation })).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
