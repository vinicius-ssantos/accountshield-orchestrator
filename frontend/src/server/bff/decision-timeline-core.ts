import {
  investigateDecision,
  type AccountShieldGeneratedTransport,
  type GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  ChallengeSummary,
  DecisionSummaryResponse,
  DecisionTimelineRequest,
  DecisionTimelineResponse,
  ExecutionProvenanceEvidence,
  InvestigationSections,
  OutboxSummary,
  PolicyProvenanceEvidence,
  ReasonEvidence,
  RecoverySummary,
  SectionAvailability,
  SignalProvenanceEvidence,
  TimelineEntry,
} from "@/generated/accountshield/openapi-types";

import { BffError } from "./foundation";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
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
const SECTION_AVAILABILITY = new Set(["AVAILABLE", "NOT_APPLICABLE", "UNAVAILABLE"]);
const SIGNAL_STATES = new Set(["RECORDED", "SIMULATED", "STALE", "UNAVAILABLE"]);
const PROHIBITED_KEY = /(token|secret|payload|accountReference|ipAddress|normalizedContext|fingerprint|authorization)/i;

export type DecisionTimelineInput = DecisionTimelineRequest;
export type DecisionTimelineResult = DecisionTimelineResponse & {
  readonly source: "fixtures" | "live";
};

export interface DecisionTimelineService {
  investigate(
    input: DecisionTimelineInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<DecisionTimelineResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

function requiredString(
  record: Record<string, unknown>,
  key: string,
  maximum = 256,
): string {
  const value = record[key];
  if (typeof value !== "string" || !value || value.length > maximum) malformed();
  return value as string;
}

function nullableString(
  record: Record<string, unknown>,
  key: string,
  maximum = 256,
): string | null {
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

function nullableBoolean(record: Record<string, unknown>, key: string): boolean | null {
  const value = record[key];
  if (value === null) return null;
  if (typeof value !== "boolean") malformed();
  return value;
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

function nullableInteger(
  record: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number,
): number | null {
  const value = record[key];
  if (value === null) return null;
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    malformed();
  }
  return value as number;
}

function instant(record: Record<string, unknown>, key: string): string {
  const value = requiredString(record, key, 40);
  if (Number.isNaN(Date.parse(value))) malformed();
  return new Date(value).toISOString();
}

function nullableInstant(record: Record<string, unknown>, key: string): string | null {
  const value = nullableString(record, key, 40);
  if (value === null) return null;
  if (Number.isNaN(Date.parse(value))) malformed();
  return new Date(value).toISOString();
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

function parseDecision(value: unknown): DecisionSummaryResponse {
  if (!isRecord(value)) malformed();
  const eventType = requiredString(value, "eventType", 64);
  const outcome = requiredString(value, "outcome", 64);
  const riskBand = requiredString(value, "riskBand", 32);
  if (!EVENT_TYPES.has(eventType) || !OUTCOMES.has(outcome) || !RISK_BANDS.has(riskBand)) {
    malformed();
  }
  return {
    decisionReference: requiredString(value, "decisionReference", 128),
    correlationId: requiredString(value, "correlationId", 128),
    eventType,
    outcome,
    riskScore: requiredInteger(value, "riskScore", 0, 100),
    riskBand,
    policyKey: requiredString(value, "policyKey", 80),
    policyVersion: requiredString(value, "policyVersion", 40),
    decidedAt: instant(value, "decidedAt"),
    degraded: requiredBoolean(value, "degraded"),
    simulated: requiredBoolean(value, "simulated"),
    provenanceAvailable: requiredBoolean(value, "provenanceAvailable"),
  };
}

function parseReason(value: unknown): ReasonEvidence {
  if (!isRecord(value)) malformed();
  return {
    code: requiredString(value, "code", 80),
    contribution: requiredInteger(value, "contribution", -100, 100),
    ordinal: requiredInteger(value, "ordinal", 0, 100),
  };
}

function parseSignal(value: unknown): SignalProvenanceEvidence {
  if (!isRecord(value)) malformed();
  const state = requiredString(value, "state", 32);
  if (!SIGNAL_STATES.has(state)) malformed();
  return {
    provider: nullableString(value, "provider", 80),
    observedAt: nullableInstant(value, "observedAt"),
    confidence: nullableString(value, "confidence", 32),
    schemaVersion: nullableString(value, "schemaVersion", 40),
    state,
    simulated: requiredBoolean(value, "simulated"),
    integrityAvailable: requiredBoolean(value, "integrityAvailable"),
  };
}

function parsePolicy(value: unknown): PolicyProvenanceEvidence {
  if (!isRecord(value)) malformed();
  return {
    policyKey: requiredString(value, "policyKey", 80),
    policyVersion: requiredString(value, "policyVersion", 40),
    routingReason: requiredString(value, "routingReason", 80),
    rolloutCohortBucket: nullableInteger(value, "rolloutCohortBucket", 0, 99_999),
    rolloutCandidateVersion: nullableString(value, "rolloutCandidateVersion", 40),
    rolloutCandidateSelected: nullableBoolean(value, "rolloutCandidateSelected"),
  };
}

function parseExecution(value: unknown): ExecutionProvenanceEvidence {
  if (!isRecord(value)) malformed();
  return {
    algorithmVersion: requiredString(value, "algorithmVersion", 80),
    normalizedInputSchemaVersion: nullableString(value, "normalizedInputSchemaVersion", 80),
    reasonCatalogVersion: nullableString(value, "reasonCatalogVersion", 80),
    decisionEngineVersion: nullableString(value, "decisionEngineVersion", 80),
    applicationCommitSha: nullableString(value, "applicationCommitSha", 80),
    canonicalInputHashAvailable: requiredBoolean(value, "canonicalInputHashAvailable"),
    auditRecordHashAvailable: requiredBoolean(value, "auditRecordHashAvailable"),
  };
}

function parseChallenge(value: unknown): ChallengeSummary {
  if (!isRecord(value)) malformed();
  return {
    reference: requiredString(value, "reference", 128),
    challengeType: requiredString(value, "challengeType", 64),
    purpose: requiredString(value, "purpose", 64),
    status: requiredString(value, "status", 64),
    createdAt: instant(value, "createdAt"),
    expiresAt: instant(value, "expiresAt"),
    consumedAt: nullableInstant(value, "consumedAt"),
  };
}

function parseRecovery(value: unknown): RecoverySummary {
  if (value === null) return null;
  if (!isRecord(value)) malformed();
  return {
    reference: requiredString(value, "reference", 128),
    directive: requiredString(value, "directive", 80),
    status: requiredString(value, "status", 64),
    issuedAt: instant(value, "issuedAt"),
    expiresAt: instant(value, "expiresAt"),
    consumedAt: nullableInstant(value, "consumedAt"),
  };
}

function parseOutbox(value: unknown): OutboxSummary {
  if (!isRecord(value)) malformed();
  return {
    reference: requiredString(value, "reference", 128),
    eventType: requiredString(value, "eventType", 80),
    status: requiredString(value, "status", 64),
    occurredAt: instant(value, "occurredAt"),
    publishedAt: nullableInstant(value, "publishedAt"),
    deadLetteredAt: nullableInstant(value, "deadLetteredAt"),
    attemptCount: requiredInteger(value, "attemptCount", 0, 10_000),
  };
}

function parseTimelineEntry(value: unknown): TimelineEntry {
  if (!isRecord(value)) malformed();
  return {
    reference: requiredString(value, "reference", 128),
    kind: requiredString(value, "kind", 64),
    status: requiredString(value, "status", 64),
    occurredAt: instant(value, "occurredAt"),
  };
}

function parseSections(value: unknown): InvestigationSections {
  if (!isRecord(value)) malformed();
  const challenge = requiredString(value, "challenge", 32);
  const recovery = requiredString(value, "recovery", 32);
  const outbox = requiredString(value, "outbox", 32);
  if (
    !SECTION_AVAILABILITY.has(challenge) ||
    !SECTION_AVAILABILITY.has(recovery) ||
    !SECTION_AVAILABILITY.has(outbox)
  ) {
    malformed();
  }
  return {
    challenge: challenge as SectionAvailability,
    recovery: recovery as SectionAvailability,
    outbox: outbox as SectionAvailability,
  };
}

export function parseDecisionTimelineInput(value: Record<string, unknown>): DecisionTimelineInput {
  if (Object.keys(value).some((key) => key !== "decisionReference")) {
    throw new BffError("INVALID_REQUEST", 400, "The investigation request is invalid.");
  }
  const decisionReference = value.decisionReference;
  if (typeof decisionReference !== "string" || !UUID_PATTERN.test(decisionReference)) {
    throw new BffError("INVALID_REQUEST", 400, "The investigation request is invalid.");
  }
  return { decisionReference: decisionReference.toLowerCase() };
}

export function parseDecisionTimelineResponse(value: unknown): DecisionTimelineResponse {
  assertNoProhibitedFields(value);
  if (!isRecord(value)) malformed();
  if (
    !Array.isArray(value.reasons) ||
    !Array.isArray(value.challenges) ||
    !Array.isArray(value.outboxEvents) ||
    !Array.isArray(value.timeline)
  ) {
    malformed();
  }

  const reasons = value.reasons.map(parseReason);
  for (let index = 1; index < reasons.length; index += 1) {
    if (reasons[index - 1]!.ordinal >= reasons[index]!.ordinal) malformed();
  }

  const timeline = value.timeline.map(parseTimelineEntry);
  for (let index = 1; index < timeline.length; index += 1) {
    if (timeline[index - 1]!.occurredAt > timeline[index]!.occurredAt) malformed();
  }

  return {
    decision: parseDecision(value.decision),
    maskedSubjectReference: requiredString(value, "maskedSubjectReference", 128),
    reasons,
    signalProvenance: parseSignal(value.signalProvenance),
    policyProvenance: parsePolicy(value.policyProvenance),
    executionProvenance: parseExecution(value.executionProvenance),
    challenges: value.challenges.map(parseChallenge),
    recovery: parseRecovery(value.recovery),
    outboxEvents: value.outboxEvents.map(parseOutbox),
    timeline,
    sections: parseSections(value.sections),
    partial: requiredBoolean(value, "partial"),
  };
}

export class AccountShieldDecisionTimelineClient implements DecisionTimelineService {
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
    input: DecisionTimelineInput,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<DecisionTimelineResult> {
    const transport: AccountShieldGeneratedTransport = {
      request: <TResponse>(request: GeneratedTransportRequest) =>
        this.execute<TResponse>(request, correlationId),
    };
    const response = await investigateDecision(transport, input, signal);
    return { ...parseDecisionTimelineResponse(response), source: "live" };
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
        throw new BffError("UPSTREAM_TIMEOUT", 504, "The decision service timed out.", true, {
          cause: error,
        });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The decision service is unavailable.", true, {
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
      throw new BffError("INVALID_REQUEST", 400, "The investigation request is invalid.");
    }
    if (response.status === 404) {
      throw new BffError("NOT_FOUND", 404, "The decision investigation was not found.");
    }
    if (response.status === 429) {
      throw new BffError("RATE_LIMITED", 429, "The decision service is temporarily rate limited.", true);
    }
    if (response.status !== request.expectedStatus) {
      throw new BffError("UPSTREAM_UNAVAILABLE", 503, "The decision service is unavailable.", true);
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
