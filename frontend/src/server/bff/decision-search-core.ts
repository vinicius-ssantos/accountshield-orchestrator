import "server-only";

import { BffError } from "./foundation";

const EVENT_TYPES = new Set([
  "LOGIN_ATTEMPT",
  "SENSITIVE_ACTION",
  "LOGIN_RECOVERY_ATTEMPT",
  "PASSWORD_RESET_ATTEMPT",
  "CREDENTIAL_CHANGE_ATTEMPT",
  "DEVICE_TRUST_RESET_ATTEMPT",
]);
const OUTCOMES = new Set([
  "ALLOW",
  "REQUIRE_STEP_UP",
  "START_RECOVERY",
  "TEMPORARILY_BLOCK",
]);
const RISK_BANDS = new Set(["LOW", "MEDIUM", "HIGH"]);
const CORRELATION_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;

export interface DecisionSearchInput {
  correlationId?: string;
  eventType?: string;
  outcome?: string;
  riskBand?: string;
  policyVersion?: string;
  decidedFrom?: string;
  decidedTo?: string;
  cursor?: string;
  pageSize?: number;
}

export interface DecisionSearchItem {
  decisionReference: string;
  correlationId: string;
  eventType: string;
  outcome: string;
  riskScore: number;
  riskBand: string;
  policyKey: string;
  policyVersion: string;
  decidedAt: string;
  degraded: boolean;
  simulated: boolean;
  provenanceAvailable: boolean;
}

export interface DecisionSearchResult {
  decisions: readonly DecisionSearchItem[];
  nextCursor?: string;
  pageSize: number;
  hasMore: boolean;
  source: "live";
  partial: boolean;
}

function optionalString(value: unknown, maximum: number): string | undefined {
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value !== "string") {
    throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
  }
  const normalized = value.trim();
  if (!normalized || normalized.length > maximum) {
    throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
  }
  return normalized;
}

function optionalEnum(value: unknown, allowed: ReadonlySet<string>): string | undefined {
  const normalized = optionalString(value, 64);
  if (normalized && !allowed.has(normalized)) {
    throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
  }
  return normalized;
}

function optionalInstant(value: unknown): string | undefined {
  const normalized = optionalString(value, 40);
  if (normalized && Number.isNaN(Date.parse(normalized))) {
    throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
  }
  return normalized ? new Date(normalized).toISOString() : undefined;
}

export function parseDecisionSearchInput(value: Record<string, unknown>): DecisionSearchInput {
  const correlationId = optionalString(value.correlationId, 128);
  if (correlationId && !CORRELATION_PATTERN.test(correlationId)) {
    throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
  }

  const pageSizeValue = value.pageSize;
  const pageSize = pageSizeValue === undefined ? 25 : pageSizeValue;
  if (!Number.isInteger(pageSize) || (pageSize as number) < 1 || (pageSize as number) > 100) {
    throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
  }

  const decidedFrom = optionalInstant(value.decidedFrom);
  const decidedTo = optionalInstant(value.decidedTo);
  if (decidedFrom && decidedTo && decidedFrom > decidedTo) {
    throw new BffError("INVALID_REQUEST", 400, "The search time range is invalid.");
  }

  return {
    correlationId,
    eventType: optionalEnum(value.eventType, EVENT_TYPES),
    outcome: optionalEnum(value.outcome, OUTCOMES),
    riskBand: optionalEnum(value.riskBand, RISK_BANDS),
    policyVersion: optionalString(value.policyVersion, 40),
    decidedFrom,
    decidedTo,
    cursor: optionalString(value.cursor, 256),
    pageSize: pageSize as number,
  };
}

function requiredString(record: Record<string, unknown>, key: string, maximum = 256): string {
  const value = record[key];
  if (typeof value !== "string" || !value || value.length > maximum) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  return value;
}

function requiredBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  return value;
}

function parseDecision(value: unknown): DecisionSearchItem {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const record = value as Record<string, unknown>;
  const riskScore = record.riskScore;
  if (!Number.isInteger(riskScore) || (riskScore as number) < 0 || (riskScore as number) > 100) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }

  const eventType = requiredString(record, "eventType", 64);
  const outcome = requiredString(record, "outcome", 64);
  const riskBand = requiredString(record, "riskBand", 32);
  const decidedAt = requiredString(record, "decidedAt", 40);
  if (!EVENT_TYPES.has(eventType) || !OUTCOMES.has(outcome) || !RISK_BANDS.has(riskBand) || Number.isNaN(Date.parse(decidedAt))) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }

  return {
    decisionReference: requiredString(record, "decisionReference", 128),
    correlationId: requiredString(record, "correlationId", 128),
    eventType,
    outcome,
    riskScore: riskScore as number,
    riskBand,
    policyKey: requiredString(record, "policyKey", 80),
    policyVersion: requiredString(record, "policyVersion", 40),
    decidedAt: new Date(decidedAt).toISOString(),
    degraded: requiredBoolean(record, "degraded"),
    simulated: requiredBoolean(record, "simulated"),
    provenanceAvailable: requiredBoolean(record, "provenanceAvailable"),
  };
}

function parseResponse(value: unknown): DecisionSearchResult {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const record = value as Record<string, unknown>;
  if (!Array.isArray(record.decisions) || !Number.isInteger(record.pageSize) || typeof record.hasMore !== "boolean") {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const nextCursor = record.nextCursor;
  if (nextCursor !== null && nextCursor !== undefined && (typeof nextCursor !== "string" || nextCursor.length > 256)) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }

  return {
    decisions: record.decisions.map(parseDecision),
    nextCursor: typeof nextCursor === "string" ? nextCursor : undefined,
    pageSize: record.pageSize as number,
    hasMore: record.hasMore,
    source: "live",
    partial: false,
  };
}

export class AccountShieldDecisionSearchClient {
  constructor(
    private readonly configuration: {
      origin: string;
      operatorToken: string;
      timeoutMs: number;
      maxResponseBytes: number;
      fetchImplementation?: typeof fetch;
    },
  ) {}

  async search(input: DecisionSearchInput, correlationId: string, signal?: AbortSignal): Promise<DecisionSearchResult> {
    const fetchImplementation = this.configuration.fetchImplementation ?? fetch;
    const timeoutSignal = AbortSignal.timeout(this.configuration.timeoutMs);
    const requestSignal = signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal;

    let response: Response;
    try {
      response = await fetchImplementation(new URL("/api/v1/operator/decisions/search", this.configuration.origin), {
        method: "POST",
        headers: {
          accept: "application/json",
          authorization: `Bearer ${this.configuration.operatorToken}`,
          "content-type": "application/json",
          "x-correlation-id": correlationId,
        },
        body: JSON.stringify(input),
        cache: "no-store",
        signal: requestSignal,
      });
    } catch (error) {
      if (requestSignal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The decision service timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The decision service is unavailable.", true, { cause: error });
    }

    if (response.status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
    if (response.status === 403) throw new BffError("FORBIDDEN", 403, "Operator access is not permitted.");
    if (response.status === 400) throw new BffError("INVALID_REQUEST", 400, "The search request is invalid.");
    if (response.status === 429) throw new BffError("RATE_LIMITED", 429, "The decision service is temporarily rate limited.", true);
    if (!response.ok) throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The decision service is unavailable.", true);

    const body = new Uint8Array(await response.arrayBuffer());
    if (body.byteLength > this.configuration.maxResponseBytes) {
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
    }

    try {
      return parseResponse(JSON.parse(new TextDecoder().decode(body)));
    } catch (error) {
      if (error instanceof BffError) throw error;
      throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, { cause: error });
    }
  }
}
