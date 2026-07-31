import { describe, expect, it, vi } from "vitest";

import { OutboxBrowserError, searchOutboxThroughBff } from "./outbox-browser";

const RESULT = {
  health: {
    pendingCount: 1,
    retryingCount: 0,
    inProgressCount: 0,
    deadLetteredCount: 1,
    oldestPendingAgeSeconds: 10,
    recentlyDeadLetteredCount: 0,
    recentlyPublishedCount: 0,
    windowMinutes: 15,
    asOf: "2026-07-31T10:00:00.000Z",
  },
  events: {
    records: [
      {
        eventId: "00000000-0000-4000-c000-000000000001",
        aggregateType: "Recovery",
        eventType: "RECOVERY_MANUAL_REVIEW_REQUIRED",
        status: "DEAD_LETTERED",
        attemptCount: 5,
        occurredAt: "2026-07-31T09:00:00.000Z",
        publishedAt: null,
        deadLetteredAt: "2026-07-31T09:10:00.000Z",
        nextAttemptAt: null,
        claimed: false,
        claimedAt: null,
        schemaVersion: "integration-event-1.0",
        maskedCorrelationReference: "••••7a01",
        deadLetterReasonAvailable: true,
        deadLetterFailureCategory: "ConnectException",
      },
    ],
    nextCursor: null,
    pageSize: 25,
    hasMore: false,
  },
  source: "fixtures",
};

describe("browser outbox-search transport", () => {
  it("posts filters to a same-origin endpoint", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESULT), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await searchOutboxThroughBff({ statuses: ["DEAD_LETTERED"] }, { fetchImplementation });

    expect(result.events.records).toHaveLength(1);
    expect(result.events.records[0]?.deadLetterFailureCategory).toBe("ConnectException");
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/outbox-search");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ statuses: ["DEAD_LETTERED"] });
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

    const promise = searchOutboxThroughBff({}, { fetchImplementation });

    await expect(promise).rejects.toBeInstanceOf(OutboxBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      retryable: false,
      message: "Outbox search failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ health: RESULT.health, events: { records: [] } }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(searchOutboxThroughBff({}, { fetchImplementation })).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });

  it("fails closed on an unknown record status value", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({
          ...RESULT,
          events: {
            ...RESULT.events,
            records: [{ ...RESULT.events.records[0], status: "NOT_A_STATUS" }],
          },
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );

    await expect(searchOutboxThroughBff({}, { fetchImplementation })).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
