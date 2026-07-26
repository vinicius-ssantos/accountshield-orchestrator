import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldReadClient,
  RuntimeStatusService,
  type FetchLike,
} from "./runtime-status-core";

function jsonResponse(
  body: unknown,
  init: ResponseInit = {},
): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: {
      "content-type": "application/json",
      ...init.headers,
    },
  });
}

describe("AccountShieldReadClient", () => {
  it("uses one fixed read-only path and propagates only the validated correlation ID", async () => {
    const fetchImpl = vi.fn<FetchLike>(async (input, init) => {
      expect(new URL(input instanceof Request ? input.url : input).pathname).toBe(
        "/actuator/health",
      );
      expect(init?.method).toBe("GET");
      const headers = new Headers(init?.headers);
      expect(headers.get("x-correlation-id")).toBe("corr_live_1234");
      expect(headers.get("authorization")).toBeNull();
      expect(headers.get("x-forwarded-user")).toBeNull();
      return jsonResponse({ status: "UP", components: { db: "hidden" } });
    });
    const client = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 100,
      maxResponseBytes: 1_024,
      fetchImpl,
    });

    await expect(client.getRuntimeHealth("corr_live_1234")).resolves.toEqual({
      status: "UP",
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("preserves backend authorization denial without exposing Problem Details text", async () => {
    const client = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 100,
      maxResponseBytes: 1_024,
      fetchImpl: async () =>
        new Response(
          JSON.stringify({
            type: "https://backend.invalid/problems/forbidden",
            title: "Forbidden",
            detail: "account acct_raw_secret exists on postgres.internal",
            status: 403,
          }),
          {
            status: 403,
            headers: { "content-type": "application/problem+json" },
          },
        ),
    });

    const error = await client
      .getRuntimeHealth("corr_live_1234")
      .catch((caught) => caught as BffError);

    expect(error).toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      title: "The operation is not permitted.",
    });
    expect(JSON.stringify(error)).not.toContain("acct_raw_secret");
    expect(JSON.stringify(error)).not.toContain("postgres.internal");
  });

  it("maps timeout and caller abort to a stable retryable error", async () => {
    const neverCompletes: FetchLike = async (_input, init) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener(
          "abort",
          () => reject(new DOMException("Aborted", "AbortError")),
          { once: true },
        );
      });
    const client = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 5,
      maxResponseBytes: 1_024,
      fetchImpl: neverCompletes,
    });

    await expect(
      client.getRuntimeHealth("corr_timeout_1234"),
    ).rejects.toMatchObject({
      code: "UPSTREAM_TIMEOUT",
      status: 504,
      retryable: true,
    });

    const caller = new AbortController();
    const aborted = client.getRuntimeHealth("corr_abort_1234", caller.signal);
    caller.abort();
    await expect(aborted).rejects.toMatchObject({
      code: "UPSTREAM_TIMEOUT",
      status: 504,
      retryable: true,
    });
  });

  it("maps unavailable and malformed responses without retrying", async () => {
    const unavailableFetch = vi.fn<FetchLike>(async () => {
      throw new TypeError("connect ECONNREFUSED app.internal:8080");
    });
    const unavailableClient = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 100,
      maxResponseBytes: 1_024,
      fetchImpl: unavailableFetch,
    });

    await expect(
      unavailableClient.getRuntimeHealth("corr_unavailable_1234"),
    ).rejects.toMatchObject({
      code: "UPSTREAM_UNAVAILABLE",
      status: 503,
      retryable: true,
    });
    expect(unavailableFetch).toHaveBeenCalledTimes(1);

    const malformedClient = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 100,
      maxResponseBytes: 1_024,
      fetchImpl: async () =>
        new Response("not-json", {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
    });

    await expect(
      malformedClient.getRuntimeHealth("corr_malformed_1234"),
    ).rejects.toMatchObject({
      code: "UPSTREAM_MALFORMED_RESPONSE",
      status: 502,
    });
  });

  it("rejects responses above the configured boundary", async () => {
    const client = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 100,
      maxResponseBytes: 16,
      fetchImpl: async () =>
        jsonResponse(
          { status: "UP", internal: "x".repeat(128) },
          { headers: { "content-length": "256" } },
        ),
    });

    await expect(
      client.getRuntimeHealth("corr_large_1234"),
    ).rejects.toMatchObject({
      code: "UPSTREAM_MALFORMED_RESPONSE",
      status: 502,
    });
  });
});

describe("RuntimeStatusService", () => {
  it("returns deterministic fixture status without a network client", async () => {
    const service = new RuntimeStatusService({
      source: "fixtures",
      now: () => new Date("2026-07-26T12:00:00.000Z"),
    });

    await expect(service.getStatus("corr_fixture_1234")).resolves.toEqual({
      availability: "available",
      source: "fixtures",
      checkedAt: "2026-07-26T12:00:00.000Z",
      correlationId: "corr_fixture_1234",
    });
  });

  it("minimizes live health responses to the fields required by the view", async () => {
    const client = new AccountShieldReadClient({
      origin: "http://app:8080",
      timeoutMs: 100,
      maxResponseBytes: 1_024,
      fetchImpl: async () =>
        jsonResponse({
          status: "DOWN",
          components: {
            db: { details: { hostname: "postgres.internal" } },
          },
        }),
    });
    const service = new RuntimeStatusService({
      source: "live",
      client,
      now: () => new Date("2026-07-26T12:00:00.000Z"),
    });

    const result = await service.getStatus("corr_live_1234");
    expect(result).toEqual({
      availability: "degraded",
      source: "live",
      checkedAt: "2026-07-26T12:00:00.000Z",
      correlationId: "corr_live_1234",
    });
    expect(JSON.stringify(result)).not.toContain("postgres.internal");
    expect(JSON.stringify(result)).not.toContain("components");
  });
});
