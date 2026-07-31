import {
  searchOutboxOperations,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  OutboxEventPageResponse,
  OutboxEventRecordResponse,
  OutboxHealthResponse,
  OutboxSearchRequest,
  OutboxSearchResponse,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

const STATUS_VALUES = new Set(["PENDING", "IN_PROGRESS", "PUBLISHED", "DEAD_LETTERED"]);
const ALLOWED_REQUEST_KEYS = new Set([
  "statuses",
  "eventType",
  "occurredFrom",
  "occurredTo",
  "minAttemptCount",
  "maxAttemptCount",
  "cursor",
  "pageSize",
]);
const MAX_ATTEMPT_COUNT_BOUND = 1000;
const MAX_PAGE_SIZE = 100;

/**
 * `payload`/`lastError`/`claimedBy` must never cross this boundary (ADR 0045) -- defense in
 * depth even though the backend contract already excludes them.
 */
const PROHIBITED_KEY_PATTERN = /(payload|lasterror|claimedby|secret|token|ipaddress|stacktrace|authorization)/i;

export type OutboxSearchInput = OutboxSearchRequest;
export type OutboxSearchResult = OutboxSearchResponse & { readonly source: "fixtures" | "live" };

export interface OutboxSearchService {
  search(input: OutboxSearchInput, correlationId: string, signal?: AbortSignal): Promise<OutboxSearchResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

function invalidRequest(): never {
  throw new BffError("INVALID_REQUEST", 400, "The outbox search request is invalid.");
}

function assertNoProhibitedFields(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(assertNoProhibitedFields);
    return;
  }
  if (!isRecord(value)) return;
  for (const [key, nested] of Object.entries(value)) {
    if (PROHIBITED_KEY_PATTERN.test(key)) malformed();
    assertNoProhibitedFields(nested);
  }
}

function optionalBoundedString(
  value: Record<string, unknown>,
  key: string,
  maxLength: number,
): string | undefined {
  const raw = value[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== "string" || raw.length < 1 || raw.length > maxLength) invalidRequest();
  return raw;
}

function optionalIsoDate(value: Record<string, unknown>, key: string): string | undefined {
  const raw = value[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== "string" || Number.isNaN(Date.parse(raw))) invalidRequest();
  return raw;
}

function optionalBoundedInteger(
  value: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number,
): number | undefined {
  const raw = value[key];
  if (raw === undefined || raw === null) return undefined;
  if (!Number.isInteger(raw) || (raw as number) < minimum || (raw as number) > maximum) invalidRequest();
  return raw as number;
}

export function parseOutboxSearchInput(value: Record<string, unknown>): OutboxSearchInput {
  if (Object.keys(value).some((key) => !ALLOWED_REQUEST_KEYS.has(key))) invalidRequest();

  let statuses: readonly string[] | undefined;
  if (value.statuses !== undefined && value.statuses !== null) {
    if (!Array.isArray(value.statuses) || value.statuses.some((status) => !STATUS_VALUES.has(status as string))) {
      invalidRequest();
    }
    statuses = value.statuses as readonly string[];
  }

  const eventType = optionalBoundedString(value, "eventType", 160);
  const occurredFrom = optionalIsoDate(value, "occurredFrom");
  const occurredTo = optionalIsoDate(value, "occurredTo");
  const minAttemptCount = optionalBoundedInteger(value, "minAttemptCount", 0, MAX_ATTEMPT_COUNT_BOUND);
  const maxAttemptCount = optionalBoundedInteger(value, "maxAttemptCount", 0, MAX_ATTEMPT_COUNT_BOUND);
  if (minAttemptCount !== undefined && maxAttemptCount !== undefined && minAttemptCount > maxAttemptCount) {
    invalidRequest();
  }
  const cursor = optionalBoundedString(value, "cursor", 256);
  const pageSize = optionalBoundedInteger(value, "pageSize", 1, MAX_PAGE_SIZE);

  return {
    statuses,
    eventType,
    occurredFrom,
    occurredTo,
    minAttemptCount,
    maxAttemptCount,
    cursor,
    pageSize,
  };
}

function requiredInteger(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (!Number.isInteger(value)) malformed();
  return value as number;
}

function requiredBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") malformed();
  return value;
}

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== "string" || !value) malformed();
  return value;
}

function nullableString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || !value) malformed();
  return value;
}

function nullableNumber(record: Record<string, unknown>, key: string): number | null {
  const value = record[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "number" || !Number.isFinite(value)) malformed();
  return value;
}

function parseHealth(value: unknown): OutboxHealthResponse {
  if (!isRecord(value)) malformed();
  return {
    pendingCount: requiredInteger(value, "pendingCount"),
    retryingCount: requiredInteger(value, "retryingCount"),
    inProgressCount: requiredInteger(value, "inProgressCount"),
    deadLetteredCount: requiredInteger(value, "deadLetteredCount"),
    oldestPendingAgeSeconds: nullableNumber(value, "oldestPendingAgeSeconds"),
    recentlyDeadLetteredCount: requiredInteger(value, "recentlyDeadLetteredCount"),
    recentlyPublishedCount: requiredInteger(value, "recentlyPublishedCount"),
    windowMinutes: requiredInteger(value, "windowMinutes"),
    asOf: requiredString(value, "asOf"),
  };
}

function parseRecord(value: unknown): OutboxEventRecordResponse {
  if (!isRecord(value)) malformed();
  const status = requiredString(value, "status");
  if (!STATUS_VALUES.has(status)) malformed();
  return {
    eventId: requiredString(value, "eventId"),
    aggregateType: requiredString(value, "aggregateType"),
    eventType: requiredString(value, "eventType"),
    status: status as OutboxEventRecordResponse["status"],
    attemptCount: requiredInteger(value, "attemptCount"),
    occurredAt: requiredString(value, "occurredAt"),
    publishedAt: nullableString(value, "publishedAt"),
    deadLetteredAt: nullableString(value, "deadLetteredAt"),
    nextAttemptAt: nullableString(value, "nextAttemptAt"),
    claimed: requiredBoolean(value, "claimed"),
    claimedAt: nullableString(value, "claimedAt"),
    schemaVersion: nullableString(value, "schemaVersion"),
    maskedCorrelationReference: requiredString(value, "maskedCorrelationReference"),
    deadLetterReasonAvailable: requiredBoolean(value, "deadLetterReasonAvailable"),
    deadLetterFailureCategory: nullableString(value, "deadLetterFailureCategory"),
  };
}

function parseEvents(value: unknown): OutboxEventPageResponse {
  if (!isRecord(value) || !Array.isArray(value.records)) malformed();
  return {
    records: value.records.map(parseRecord),
    nextCursor: nullableString(value, "nextCursor"),
    pageSize: requiredInteger(value, "pageSize"),
    hasMore: requiredBoolean(value, "hasMore"),
  };
}

export function parseOutboxSearchResponse(value: unknown): OutboxSearchResponse {
  assertNoProhibitedFields(value);
  if (!isRecord(value)) malformed();
  return {
    health: parseHealth(value.health),
    events: parseEvents(value.events),
  };
}

export class AccountShieldOutboxSearchClient implements OutboxSearchService {
  constructor(
    private readonly configuration: {
      origin: string;
      operatorToken: string;
      timeoutMs: number;
      maxResponseBytes: number;
      fetchImplementation?: typeof fetch;
    },
  ) {}

  async search(
    input: OutboxSearchInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<OutboxSearchResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response = await searchOutboxOperations(transport, input, signal);
    return { ...parseOutboxSearchResponse(response), source: "live" };
  }

  private async execute<TResponse>(
    request: GeneratedTransportRequest,
    correlationId: string,
  ): Promise<TResponse> {
    const fetchImplementation = this.configuration.fetchImplementation ?? fetch;
    const timeoutSignal = AbortSignal.timeout(this.configuration.timeoutMs);
    const requestSignal = request.signal
      ? AbortSignal.any([request.signal, timeoutSignal])
      : timeoutSignal;

    let response: Response;
    try {
      response = await fetchImplementation(new URL(request.path, this.configuration.origin), {
        method: request.method,
        headers: {
          accept: "application/json",
          authorization: `Bearer ${this.configuration.operatorToken}`,
          "content-type": "application/json",
          "x-correlation-id": correlationId,
        },
        body: request.body === undefined ? undefined : JSON.stringify(request.body),
        cache: "no-store",
        signal: requestSignal,
      });
    } catch (error) {
      if (requestSignal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The outbox service timed out.", true, {
          cause: error,
        });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The outbox service is unavailable.", true, {
        cause: error,
      });
    }

    if (response.status === 401) {
      throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
    }
    if (response.status === 403) {
      throw new BffError("FORBIDDEN", 403, "Operator access is not permitted.");
    }
    if (response.status === 400) {
      throw new BffError("INVALID_REQUEST", 400, "The outbox search request is invalid.");
    }
    if (response.status === 429) {
      throw new BffError("RATE_LIMITED", 429, "The outbox service is temporarily rate limited.", true);
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The outbox service is unavailable.", true);
    }

    const declaredLength = Number.parseInt(response.headers.get("content-length") ?? "", 10);
    if (Number.isFinite(declaredLength) && declaredLength > this.configuration.maxResponseBytes) {
      malformed();
    }
    const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
    if (contentType !== "application/json") malformed();

    const body = new Uint8Array(await response.arrayBuffer());
    if (body.byteLength > this.configuration.maxResponseBytes) malformed();
    try {
      return JSON.parse(new TextDecoder().decode(body)) as TResponse;
    } catch (error) {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
        cause: error,
      });
    }
  }
}
