import {
  investigatePolicy,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  PolicyInvestigationRequest,
  PolicyInvestigationResponse,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

const POLICY_KEY_PATTERN = /^.{1,100}$/;
const IMPACT_AVAILABILITY = new Set(["AVAILABLE", "NOT_APPLICABLE", "UNAVAILABLE"]);
const LIFECYCLE_STATUS = new Set(["DRAFT", "VALIDATED", "APPROVED", "ACTIVE", "RETIRED", "REJECTED"]);
const ROLLOUT_STATUS = new Set(["ACTIVE", "ROLLED_BACK"]);

/**
 * `accountReference`/`protectionRequestId` are matched as exact keys, not substrings, because
 * this response's legitimately safe fields (`redactedAccountReference`,
 * `maskedProtectionRequestReference`) contain those words as substrings.
 */
const PROHIBITED_SUBSTRING =
  /(token|secret|payload|ipAddress|normalizedContext|fingerprint|authorization|providerPayload|stackTrace|challengeCode)/i;
const PROHIBITED_EXACT_KEYS = new Set(["accountReference", "protectionRequestId"]);

export type PolicyInvestigationInput = PolicyInvestigationRequest;
export type PolicyInvestigationResult = PolicyInvestigationResponse & {
  readonly source: "fixtures" | "live";
};

export interface PolicyInvestigationService {
  investigate(
    input: PolicyInvestigationInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<PolicyInvestigationResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

function assertNoProhibitedFields(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(assertNoProhibitedFields);
    return;
  }
  if (!isRecord(value)) return;
  for (const [key, nested] of Object.entries(value)) {
    if (PROHIBITED_EXACT_KEYS.has(key) || PROHIBITED_SUBSTRING.test(key)) malformed();
    assertNoProhibitedFields(nested);
  }
}

function requiredString(record: Record<string, unknown>, key: string, maximum = 256): string {
  const value = record[key];
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value as string;
}

function requiredEnum(record: Record<string, unknown>, key: string, allowed: ReadonlySet<string>): string {
  const value = requiredString(record, key, 32);
  if (!allowed.has(value)) malformed();
  return value;
}

function requiredBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") malformed();
  return value as boolean;
}

function requiredInteger(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (!Number.isInteger(value)) malformed();
  return value as number;
}

function isRecordArray(value: unknown): value is unknown[] {
  return Array.isArray(value);
}

export function parsePolicyInvestigationInput(value: Record<string, unknown>): PolicyInvestigationInput {
  if (Object.keys(value).some((key) => key !== "policyKey")) {
    throw new BffError("INVALID_REQUEST", 400, "The policy investigation request is invalid.");
  }
  const policyKey = value.policyKey;
  if (typeof policyKey !== "string" || !POLICY_KEY_PATTERN.test(policyKey)) {
    throw new BffError("INVALID_REQUEST", 400, "The policy investigation request is invalid.");
  }
  return { policyKey };
}

export function parsePolicyInvestigationResponse(value: unknown): PolicyInvestigationResponse {
  assertNoProhibitedFields(value);
  if (!isRecord(value)) malformed();
  if (!isRecordArray(value.versions) || !isRecordArray(value.routingScope)) malformed();

  value.versions.forEach((version) => {
    if (!isRecord(version)) malformed();
    requiredString(version, "id", 40);
    requiredString(version, "policyKey", 100);
    requiredString(version, "version", 40);
    requiredEnum(version, "status", LIFECYCLE_STATUS);
    requiredString(version, "createdAt", 40);
  });
  value.routingScope.forEach((entry) => {
    if (!isRecord(entry)) malformed();
    requiredString(entry, "clientId", 100);
    requiredString(entry, "eventType", 64);
  });

  const activeRollout = value.activeRollout;
  if (activeRollout !== null && activeRollout !== undefined) {
    if (!isRecord(activeRollout)) malformed();
    requiredString(activeRollout, "candidateVersion", 40);
    requiredInteger(activeRollout, "rolloutPercentage");
    requiredEnum(activeRollout, "status", ROLLOUT_STATUS);
  }

  const impactAvailability = requiredEnum(value, "impactAvailability", IMPACT_AVAILABILITY);
  const impactAnalysis = value.impactAnalysis;
  if (impactAvailability === "AVAILABLE") {
    if (!isRecord(impactAnalysis)) malformed();
    requiredString(impactAnalysis, "candidatePolicyVersion", 40);
    requiredInteger(impactAnalysis, "totalDecisions");
    requiredBoolean(impactAnalysis, "exceedsDivergenceThreshold");
    if (!isRecordArray(impactAnalysis.divergentDecisions)) malformed();
    impactAnalysis.divergentDecisions.forEach((decision) => {
      if (!isRecord(decision)) malformed();
      requiredString(decision, "maskedProtectionRequestReference", 40);
      requiredString(decision, "redactedAccountReference", 128);
    });
  } else if (impactAnalysis !== null && impactAnalysis !== undefined) {
    malformed();
  }

  return value as unknown as PolicyInvestigationResponse;
}

export class AccountShieldPolicyInvestigationClient implements PolicyInvestigationService {
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
    input: PolicyInvestigationInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<PolicyInvestigationResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response = await investigatePolicy(transport, input, signal);
    return { ...parsePolicyInvestigationResponse(response), source: "live" };
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
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The policy service timed out.", true, {
          cause: error,
        });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The policy service is unavailable.", true, {
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
      throw new BffError("INVALID_REQUEST", 400, "The policy investigation request is invalid.");
    }
    if (response.status === 404) {
      throw new BffError("NOT_FOUND", 404, "The policy investigation was not found.");
    }
    if (response.status === 429) {
      throw new BffError("RATE_LIMITED", 429, "The policy service is temporarily rate limited.", true);
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The policy service is unavailable.", true);
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
