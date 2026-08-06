import { OutboxStatusValues } from "./types";
import type { OutboxEventRecord, OutboxSearchFilters, OutboxSearchResult, OutboxStatus } from "./types";

const ENDPOINT = "/api/bff/outbox-search";
const STATUS_SET = new Set<string>(OutboxStatusValues);

export class OutboxBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Outbox search failed.");
    this.name = "OutboxBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isStatus(value: unknown): value is OutboxStatus {
  return typeof value === "string" && STATUS_SET.has(value);
}

function parseRecord(value: unknown): OutboxEventRecord | null {
  if (!isRecord(value)) return null;
  if (
    typeof value.eventId !== "string" ||
    typeof value.aggregateType !== "string" ||
    typeof value.eventType !== "string" ||
    !isStatus(value.status) ||
    !Number.isInteger(value.attemptCount) ||
    typeof value.occurredAt !== "string" ||
    typeof value.claimed !== "boolean" ||
    typeof value.maskedCorrelationReference !== "string" ||
    typeof value.deadLetterReasonAvailable !== "boolean"
  ) {
    return null;
  }
  if (
    !isNullableString(value.publishedAt) ||
    !isNullableString(value.deadLetteredAt) ||
    !isNullableString(value.nextAttemptAt) ||
    !isNullableString(value.claimedAt) ||
    !isNullableString(value.schemaVersion) ||
    !isNullableString(value.deadLetterFailureCategory)
  ) {
    return null;
  }

  return {
    eventId: value.eventId as string,
    aggregateType: value.aggregateType as string,
    eventType: value.eventType as string,
    status: value.status,
    attemptCount: value.attemptCount as number,
    occurredAt: value.occurredAt as string,
    publishedAt: value.publishedAt,
    deadLetteredAt: value.deadLetteredAt,
    nextAttemptAt: value.nextAttemptAt,
    claimed: value.claimed as boolean,
    claimedAt: value.claimedAt,
    schemaVersion: value.schemaVersion,
    maskedCorrelationReference: value.maskedCorrelationReference as string,
    deadLetterReasonAvailable: value.deadLetterReasonAvailable,
    deadLetterFailureCategory: value.deadLetterFailureCategory,
  };
}

function parseResult(value: unknown): OutboxSearchResult {
  if (!isRecord(value) || !isRecord(value.health) || !isRecord(value.events)) {
    throw new OutboxBrowserError("MALFORMED_RESPONSE", 502, false);
  }
  const health = value.health;
  const events = value.events;

  if (
    !Number.isInteger(health.pendingCount) ||
    !Number.isInteger(health.retryingCount) ||
    !Number.isInteger(health.inProgressCount) ||
    !Number.isInteger(health.deadLetteredCount) ||
    (health.oldestPendingAgeSeconds !== null && typeof health.oldestPendingAgeSeconds !== "number") ||
    !Number.isInteger(health.recentlyDeadLetteredCount) ||
    !Number.isInteger(health.recentlyPublishedCount) ||
    !Number.isInteger(health.windowMinutes) ||
    typeof health.asOf !== "string"
  ) {
    throw new OutboxBrowserError("MALFORMED_RESPONSE", 502, false);
  }

  if (!Array.isArray(events.records)) {
    throw new OutboxBrowserError("MALFORMED_RESPONSE", 502, false);
  }
  const records = events.records.map(parseRecord);
  if (
    records.some((record) => record === null) ||
    !Number.isInteger(events.pageSize) ||
    typeof events.hasMore !== "boolean" ||
    (events.nextCursor !== undefined && events.nextCursor !== null && typeof events.nextCursor !== "string") ||
    (value.source !== "fixtures" && value.source !== "live")
  ) {
    throw new OutboxBrowserError("MALFORMED_RESPONSE", 502, false);
  }

  return {
    health: {
      pendingCount: health.pendingCount as number,
      retryingCount: health.retryingCount as number,
      inProgressCount: health.inProgressCount as number,
      deadLetteredCount: health.deadLetteredCount as number,
      oldestPendingAgeSeconds: health.oldestPendingAgeSeconds as number | null,
      recentlyDeadLetteredCount: health.recentlyDeadLetteredCount as number,
      recentlyPublishedCount: health.recentlyPublishedCount as number,
      windowMinutes: health.windowMinutes as number,
      asOf: health.asOf as string,
    },
    events: {
      records: records as OutboxEventRecord[],
      nextCursor: typeof events.nextCursor === "string" ? events.nextCursor : undefined,
      pageSize: events.pageSize as number,
      hasMore: events.hasMore,
    },
    source: value.source,
  };
}

async function safeProblem(response: Response): Promise<OutboxBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new OutboxBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed upstream/browser-facing details.
  }
  return new OutboxBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function searchOutboxThroughBff(
  filters: OutboxSearchFilters,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<OutboxSearchResult> {
  const fetchImplementation = options.fetchImplementation ?? fetch;
  const response = await fetchImplementation(ENDPOINT, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify(filters),
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });

  if (!response.ok) throw await safeProblem(response);
  return parseResult((await response.json()) as unknown);
}
