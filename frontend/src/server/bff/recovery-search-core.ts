import {
  searchRecoveryInvestigations,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  RecoverySearchRequest,
  RecoverySearchResponse,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

const STATUSES = new Set([
  "INITIATED",
  "VERIFYING_IDENTITY",
  "IDENTITY_VERIFIED",
  "DELAYED",
  "MANUAL_REVIEW",
  "COMPLETED",
  "IDENTITY_FAILED",
  "REJECTED",
  "ABORTED",
]);
const CLASSIFICATIONS = new Set(["IMMEDIATE", "DELAYED", "MANUAL_REVIEW"]);
const EVENT_TYPES = new Set(["LOGIN", "PASSWORD_RESET", "CREDENTIAL_CHANGE", "DEVICE_TRUST_RESET"]);
const REVIEW_STATES = new Set(["PENDING", "REVIEWED", "NOT_APPLICABLE"]);

export type RecoverySearchInput = RecoverySearchRequest;

export interface RecoverySearchSummary {
  recoveryReference: string;
  maskedSubjectReference: string;
  eventType: string;
  status: string;
  terminal: boolean;
  classification: string;
  classificationRuleVersion: string;
  riskScore: number;
  initiatedAt: string;
  updatedAt: string;
  eligibleAfter: string | null;
  originatingDecisionReference: string;
  reviewState: string;
  challengeExpected: boolean;
}

export interface RecoverySearchResult {
  recoveries: readonly RecoverySearchSummary[];
  nextCursor?: string;
  pageSize: number;
  hasMore: boolean;
  source: "fixtures" | "live";
  partial: boolean;
}

export interface RecoverySearchService {
  search(
    input: RecoverySearchInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RecoverySearchResult>;
}

function optionalString(value: unknown, maximum: number): string | undefined {
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value !== "string") {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
  }
  const normalized = value.trim();
  if (!normalized || normalized.length > maximum) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
  }
  return normalized;
}

function optionalEnum(value: unknown, allowed: ReadonlySet<string>): string | undefined {
  const normalized = optionalString(value, 32);
  if (normalized && !allowed.has(normalized)) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
  }
  return normalized;
}

function optionalInstant(value: unknown): string | undefined {
  const normalized = optionalString(value, 40);
  if (normalized && Number.isNaN(Date.parse(normalized))) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
  }
  return normalized ? new Date(normalized).toISOString() : undefined;
}

function optionalRiskScore(value: unknown): number | undefined {
  if (value === undefined || value === null) return undefined;
  if (!Number.isInteger(value) || (value as number) < 0 || (value as number) > 100) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
  }
  return value as number;
}

export function parseRecoverySearchInput(value: Record<string, unknown>): RecoverySearchInput {
  const pageSizeValue = value.pageSize;
  const pageSize = pageSizeValue === undefined ? 25 : pageSizeValue;
  if (!Number.isInteger(pageSize) || (pageSize as number) < 1 || (pageSize as number) > 100) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
  }

  const initiatedFrom = optionalInstant(value.initiatedFrom);
  const initiatedTo = optionalInstant(value.initiatedTo);
  if (initiatedFrom && initiatedTo && initiatedFrom > initiatedTo) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search time range is invalid.");
  }
  const eligibleFrom = optionalInstant(value.eligibleFrom);
  const eligibleTo = optionalInstant(value.eligibleTo);
  if (eligibleFrom && eligibleTo && eligibleFrom > eligibleTo) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery search time range is invalid.");
  }

  return {
    status: optionalEnum(value.status, STATUSES),
    classification: optionalEnum(value.classification, CLASSIFICATIONS),
    eventType: optionalEnum(value.eventType, EVENT_TYPES),
    reviewState: optionalEnum(value.reviewState, REVIEW_STATES),
    initiatedFrom,
    initiatedTo,
    eligibleFrom,
    eligibleTo,
    minimumRiskScore: optionalRiskScore(value.minimumRiskScore),
    maximumRiskScore: optionalRiskScore(value.maximumRiskScore),
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

function nullableString(record: Record<string, unknown>, key: string, maximum = 256): string | null {
  const value = record[key];
  if (value === null) return null;
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

function parseSummary(value: unknown): RecoverySearchSummary {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const record = value as Record<string, unknown>;
  const riskScore = record.riskScore;
  if (!Number.isInteger(riskScore) || (riskScore as number) < 0 || (riskScore as number) > 100) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }

  return {
    recoveryReference: requiredString(record, "recoveryReference", 128),
    maskedSubjectReference: requiredString(record, "maskedSubjectReference", 128),
    eventType: requiredString(record, "eventType", 32),
    status: requiredString(record, "status", 32),
    terminal: requiredBoolean(record, "terminal"),
    classification: requiredString(record, "classification", 32),
    classificationRuleVersion: requiredString(record, "classificationRuleVersion", 64),
    riskScore: riskScore as number,
    initiatedAt: requiredString(record, "initiatedAt", 40),
    updatedAt: requiredString(record, "updatedAt", 40),
    eligibleAfter: nullableString(record, "eligibleAfter", 40),
    originatingDecisionReference: requiredString(record, "originatingDecisionReference", 128),
    reviewState: requiredString(record, "reviewState", 32),
    challengeExpected: requiredBoolean(record, "challengeExpected"),
  };
}

function parseResponse(value: unknown): RecoverySearchResult {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const record = value as Record<string, unknown>;
  if (
    !Array.isArray(record.recoveries) ||
    !Number.isInteger(record.pageSize) ||
    typeof record.hasMore !== "boolean"
  ) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }
  const nextCursor = record.nextCursor;
  if (
    nextCursor !== null &&
    nextCursor !== undefined &&
    (typeof nextCursor !== "string" || nextCursor.length > 256)
  ) {
    throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
  }

  return {
    recoveries: record.recoveries.map(parseSummary),
    nextCursor: typeof nextCursor === "string" ? nextCursor : undefined,
    pageSize: record.pageSize as number,
    hasMore: record.hasMore,
    source: "live",
    partial: false,
  };
}

export class AccountShieldRecoverySearchClient implements RecoverySearchService {
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
    input: RecoverySearchInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RecoverySearchResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response: RecoverySearchResponse = await searchRecoveryInvestigations(
      transport,
      input,
      signal,
    );
    return parseResponse(response);
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
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The recovery service timed out.", true, {
          cause: error,
        });
      }
      throw new BffError(
        "UPSTREAM_UNAVAILABLE",
        503,
        "The recovery service is unavailable.",
        true,
        { cause: error },
      );
    }

    if (response.status === 401) {
      throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
    }
    if (response.status === 403) {
      throw new BffError("FORBIDDEN", 403, "Operator access is not permitted.");
    }
    if (response.status === 400) {
      throw new BffError("INVALID_REQUEST", 400, "The recovery search request is invalid.");
    }
    if (response.status === 429) {
      throw new BffError(
        "RATE_LIMITED",
        429,
        "The recovery service is temporarily rate limited.",
        true,
      );
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError(
        "UPSTREAM_UNAVAILABLE",
        503,
        "The recovery service is unavailable.",
        true,
      );
    }

    const declaredLength = Number.parseInt(response.headers.get("content-length") ?? "", 10);
    if (
      Number.isFinite(declaredLength) &&
      declaredLength > this.configuration.maxResponseBytes
    ) {
      throw new BffError(
        "UPSTREAM_MALFORMED_RESPONSE",
        502,
        "The upstream response is invalid.",
      );
    }
    const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
    if (contentType !== "application/json") {
      throw new BffError(
        "UPSTREAM_MALFORMED_RESPONSE",
        502,
        "The upstream response is invalid.",
      );
    }

    const body = new Uint8Array(await response.arrayBuffer());
    if (body.byteLength > this.configuration.maxResponseBytes) {
      throw new BffError(
        "UPSTREAM_MALFORMED_RESPONSE",
        502,
        "The upstream response is invalid.",
      );
    }

    try {
      return JSON.parse(new TextDecoder().decode(body)) as TResponse;
    } catch (error) {
      throw new BffError(
        "UPSTREAM_MALFORMED_RESPONSE",
        502,
        "The upstream response is invalid.",
        false,
        { cause: error },
      );
    }
  }
}
