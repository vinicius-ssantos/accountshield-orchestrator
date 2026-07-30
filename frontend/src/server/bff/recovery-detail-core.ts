import {
  investigateRecovery,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  ChallengeEvidence,
  RecoveryInvestigationRequest,
  RecoveryInvestigationResponse,
  SectionAvailability,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SECTION_AVAILABILITY = new Set(["AVAILABLE", "NOT_APPLICABLE", "UNAVAILABLE"]);
const PROHIBITED_KEY =
  /(token|secret|payload|accountReference|ipAddress|normalizedContext|fingerprint|authorization)/i;

export type RecoveryDetailInput = RecoveryInvestigationRequest;
export type RecoveryDetailResult = RecoveryInvestigationResponse & {
  readonly source: "fixtures" | "live";
};

export interface RecoveryDetailService {
  investigate(
    input: RecoveryDetailInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RecoveryDetailResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

function requiredString(record: Record<string, unknown>, key: string, maximum = 256): string {
  const value = record[key];
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value as string;
}

function nullableString(record: Record<string, unknown>, key: string, maximum = 256): string | null {
  const value = record[key];
  if (value === null) return null;
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value;
}

function requiredBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") malformed();
  return value as boolean;
}

function requiredInteger(
  record: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number,
): number {
  const value = record[key];
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    malformed();
  }
  return value as number;
}

function assertNoProhibitedFields(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(assertNoProhibitedFields);
    return;
  }
  if (!isRecord(value)) return;
  for (const [key, nested] of Object.entries(value)) {
    if (PROHIBITED_KEY.test(key)) malformed();
    assertNoProhibitedFields(nested);
  }
}

function parseSummary(value: unknown) {
  if (!isRecord(value)) malformed();
  return {
    recoveryReference: requiredString(value, "recoveryReference", 128),
    maskedSubjectReference: requiredString(value, "maskedSubjectReference", 128),
    eventType: requiredString(value, "eventType", 32),
    status: requiredString(value, "status", 32),
    terminal: requiredBoolean(value, "terminal"),
    classification: requiredString(value, "classification", 32),
    classificationRuleVersion: requiredString(value, "classificationRuleVersion", 64),
    riskScore: requiredInteger(value, "riskScore", 0, 100),
    initiatedAt: requiredString(value, "initiatedAt", 40),
    updatedAt: requiredString(value, "updatedAt", 40),
    eligibleAfter: nullableString(value, "eligibleAfter", 40),
    originatingDecisionReference: requiredString(value, "originatingDecisionReference", 128),
    reviewState: requiredString(value, "reviewState", 32),
    challengeExpected: requiredBoolean(value, "challengeExpected"),
  };
}

function parseChallenge(value: unknown): ChallengeEvidence {
  if (!isRecord(value)) malformed();
  return {
    reference: requiredString(value, "reference", 128),
    challengeType: requiredString(value, "challengeType", 64),
    purpose: requiredString(value, "purpose", 64),
    status: requiredString(value, "status", 64),
    createdAt: requiredString(value, "createdAt", 40),
    expiresAt: requiredString(value, "expiresAt", 40),
    consumedAt: nullableString(value, "consumedAt", 40),
  };
}

export function parseRecoveryDetailInput(value: Record<string, unknown>): RecoveryDetailInput {
  if (Object.keys(value).some((key) => key !== "recoveryReference")) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery investigation request is invalid.");
  }
  const recoveryReference = value.recoveryReference;
  if (typeof recoveryReference !== "string" || !UUID_PATTERN.test(recoveryReference)) {
    throw new BffError("INVALID_REQUEST", 400, "The recovery investigation request is invalid.");
  }
  return { recoveryReference: recoveryReference.toLowerCase() };
}

export function parseRecoveryDetailResponse(value: unknown): RecoveryInvestigationResponse {
  assertNoProhibitedFields(value);
  if (!isRecord(value)) malformed();
  if (!Array.isArray(value.challenges)) malformed();

  const challengeAvailability = requiredString(value, "challengeAvailability", 32);
  if (!SECTION_AVAILABILITY.has(challengeAvailability)) malformed();

  return {
    recovery: parseSummary(value.recovery),
    protectionRequestReference: requiredString(value, "protectionRequestReference", 128),
    reviewerPresent: requiredBoolean(value, "reviewerPresent"),
    challenges: value.challenges.map(parseChallenge),
    challengeAvailability: challengeAvailability as SectionAvailability,
    partial: requiredBoolean(value, "partial"),
  };
}

export class AccountShieldRecoveryDetailClient implements RecoveryDetailService {
  constructor(
    private readonly configuration: {
      origin: string;
      operatorToken: string;
      timeoutMs: number;
      maxResponseBytes: number;
      fetchImplementation?: typeof fetch;
    },
  ) {}

  async investigate(
    input: RecoveryDetailInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RecoveryDetailResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response = await investigateRecovery(transport, input, signal);
    return { ...parseRecoveryDetailResponse(response), source: "live" };
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
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The recovery service is unavailable.", true, {
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
      throw new BffError("INVALID_REQUEST", 400, "The recovery investigation request is invalid.");
    }
    if (response.status === 404) {
      throw new BffError("NOT_FOUND", 404, "The recovery investigation was not found.");
    }
    if (response.status === 429) {
      throw new BffError("RATE_LIMITED", 429, "The recovery service is temporarily rate limited.", true);
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The recovery service is unavailable.", true);
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
