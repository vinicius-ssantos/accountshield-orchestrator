import {
  replayDecision,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  DecisionReplayRequest,
  DecisionReplayResponse,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PROHIBITED_KEY =
  /(token|secret|payload|accountReference|ipAddress|normalizedContext|fingerprint|authorization|protectionRequestId)/i;

export type DecisionReplayInput = DecisionReplayRequest;
export type DecisionReplayResult = DecisionReplayResponse & {
  readonly source: "fixtures" | "live";
};

export interface DecisionReplayService {
  replay(
    input: DecisionReplayInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<DecisionReplayResult>;
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

function parseReason(value: unknown) {
  if (!isRecord(value)) malformed();
  return {
    code: requiredString(value, "code", 64),
    contribution: requiredInteger(value, "contribution", -100, 100),
  };
}

function parseSide(value: unknown) {
  if (!isRecord(value)) malformed();
  if (!Array.isArray(value.reasons)) malformed();
  return {
    outcome: requiredString(value, "outcome", 64),
    riskScore: requiredInteger(value, "riskScore", 0, 100),
    riskBand: requiredString(value, "riskBand", 32),
    reasons: value.reasons.map(parseReason),
  };
}

export function parseDecisionReplayInput(value: Record<string, unknown>): DecisionReplayInput {
  if (Object.keys(value).some((key) => key !== "decisionReference")) {
    throw new BffError("INVALID_REQUEST", 400, "The replay request is invalid.");
  }
  const decisionReference = value.decisionReference;
  if (typeof decisionReference !== "string" || !UUID_PATTERN.test(decisionReference)) {
    throw new BffError("INVALID_REQUEST", 400, "The replay request is invalid.");
  }
  return { decisionReference: decisionReference.toLowerCase() };
}

export function parseDecisionReplayResponse(value: unknown): DecisionReplayResponse {
  assertNoProhibitedFields(value);
  if (!isRecord(value)) malformed();
  if (!Array.isArray(value.mismatches)) malformed();

  return {
    decisionReference: requiredString(value, "decisionReference", 128),
    maskedSubjectReference: requiredString(value, "maskedSubjectReference", 128),
    matches: requiredBoolean(value, "matches"),
    original: parseSide(value.original),
    replayed: parseSide(value.replayed),
    policyKey: requiredString(value, "policyKey", 80),
    policyVersion: requiredString(value, "policyVersion", 40),
    algorithmVersion: requiredString(value, "algorithmVersion", 80),
    normalizedInputSchemaVersion: nullableString(value, "normalizedInputSchemaVersion", 80),
    reasonCatalogVersion: requiredString(value, "reasonCatalogVersion", 80),
    decisionEngineVersion: requiredString(value, "decisionEngineVersion", 80),
    mismatches: value.mismatches.map((entry) => {
      if (typeof entry !== "string" || entry.length > 512) malformed();
      return entry;
    }),
  };
}

export class AccountShieldDecisionReplayClient implements DecisionReplayService {
  constructor(
    private readonly configuration: {
      origin: string;
      operatorToken: string;
      timeoutMs: number;
      maxResponseBytes: number;
      fetchImplementation?: typeof fetch;
    },
  ) {}

  async replay(
    input: DecisionReplayInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<DecisionReplayResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response = await replayDecision(transport, input, signal);
    return { ...parseDecisionReplayResponse(response), source: "live" };
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
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The replay service timed out.", true, {
          cause: error,
        });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The replay service is unavailable.", true, {
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
      throw new BffError("INVALID_REQUEST", 400, "The replay request is invalid.");
    }
    if (response.status === 404) {
      throw new BffError("NOT_FOUND", 404, "The decision replay was not found.");
    }
    if (response.status === 429) {
      throw new BffError("RATE_LIMITED", 429, "The replay service is temporarily rate limited.", true);
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The replay service is unavailable.", true);
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
