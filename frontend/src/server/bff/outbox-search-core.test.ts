import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldOutboxSearchClient,
  parseOutboxSearchInput,
  parseOutboxSearchResponse,
} from "./outbox-search-core";

const RESPONSE = {
  health: {
    pendingCount: 2,
    retryingCount: 1,
    inProgressCount: 1,
    deadLetteredCount: 3,
    oldestPendingAgeSeconds: 42,
    recentlyDeadLetteredCount: 1,
    recentlyPublishedCount: 5,
    windowMinutes: 15,
    asOf: "2026-07-31T10:00:00.000Z",
  },
  events: {
    records: [
      {
        eventId: "00000000-0000-4000-c000-000000000001",
        aggregateType: "ProtectionDecision",
        eventType: "PROTECTION_DECISION_MADE",
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
};

describe("outbox search BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldOutboxSearchClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation,
    });

    const result = await client.search({ statuses: ["DEAD_LETTERED"] }, "bff_correlation_01");

    expect(result).toMatchObject({ source: "live" });
    expect(result.health.deadLetteredCount).toBe(3);
    expect(result.events.records[0]?.maskedCorrelationReference).toBe("••••7a01");
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe("https://accountshield.internal/api/v1/operator/outbox/search");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ statuses: ["DEAD_LETTERED"] });
  });

  it("accepts a well-formed response", () => {
    expect(() => parseOutboxSearchResponse(RESPONSE)).not.toThrow();
  });

  it("rejects a raw payload, lastError, or claimedBy field crossing the boundary", () => {
    expect(() =>
      parseOutboxSearchResponse({
        ...RESPONSE,
        events: { ...RESPONSE.events, records: [{ ...RESPONSE.events.records[0], payload: "{}" }] },
      }),
    ).toThrowError(BffError);
    expect(() =>
      parseOutboxSearchResponse({
        ...RESPONSE,
        events: {
          ...RESPONSE.events,
          records: [{ ...RESPONSE.events.records[0], lastError: "connection refused" }],
        },
      }),
    ).toThrowError(BffError);
    expect(() =>
      parseOutboxSearchResponse({
        ...RESPONSE,
        events: {
          ...RESPONSE.events,
          records: [{ ...RESPONSE.events.records[0], claimedBy: "relay-instance-1" }],
        },
      }),
    ).toThrowError(BffError);
  });

  it("rejects oldestPendingAgeSeconds as zero-fabrication when nothing is pending", () => {
    expect(() =>
      parseOutboxSearchResponse({
        ...RESPONSE,
        health: { ...RESPONSE.health, oldestPendingAgeSeconds: null },
      }),
    ).not.toThrow();
  });

  it("accepts an empty request body", () => {
    expect(parseOutboxSearchInput({})).toEqual({
      statuses: undefined,
      eventType: undefined,
      occurredFrom: undefined,
      occurredTo: undefined,
      minAttemptCount: undefined,
      maxAttemptCount: undefined,
      cursor: undefined,
      pageSize: undefined,
    });
  });

  it("rejects an unknown status value", () => {
    expect(() => parseOutboxSearchInput({ statuses: ["NOT_A_STATUS"] })).toThrowError(BffError);
  });

  it("rejects minAttemptCount greater than maxAttemptCount", () => {
    expect(() => parseOutboxSearchInput({ minAttemptCount: 5, maxAttemptCount: 1 })).toThrowError(BffError);
  });

  it("rejects an out-of-range attempt count", () => {
    expect(() => parseOutboxSearchInput({ minAttemptCount: -1 })).toThrowError(BffError);
    expect(() => parseOutboxSearchInput({ maxAttemptCount: 1001 })).toThrowError(BffError);
  });

  it("rejects an unexpected top-level field", () => {
    expect(() => parseOutboxSearchInput({ extra: true })).toThrowError(BffError);
  });

  it("maps a 400 backend rejection without leaking its response body", async () => {
    const client = new AccountShieldOutboxSearchClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ detail: "internal SQL detail" }), {
          status: 400,
          headers: { "content-type": "application/problem+json" },
        }),
      ),
    });

    await expect(client.search({}, "bff_correlation_02")).rejects.toMatchObject({
      code: "INVALID_REQUEST",
      status: 400,
    });
  });
});
