import type { DecisionInvestigationDetail, DecisionSummary } from "./types";

const ENDPOINT = "/api/bff/decision-timeline";

export class DecisionTimelineBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Decision investigation failed.");
    this.name = "DecisionTimelineBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new DecisionTimelineBrowserError("MALFORMED_RESPONSE", 502, false);
}

function stringValue(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== "string" || !value) malformed();
  return value;
}

function nullableString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key];
  if (value === null) return null;
  if (typeof value !== "string" || !value) malformed();
  return value;
}

function booleanValue(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") malformed();
  return value;
}

function nullableBoolean(record: Record<string, unknown>, key: string): boolean | null {
  const value = record[key];
  if (value === null) return null;
  if (typeof value !== "boolean") malformed();
  return value;
}

function integerValue(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (!Number.isInteger(value)) malformed();
  return value as number;
}

function nullableInteger(record: Record<string, unknown>, key: string): number | null {
  const value = record[key];
  if (value === null) return null;
  if (!Number.isInteger(value)) malformed();
  return value as number;
}

function recordValue(record: Record<string, unknown>, key: string): Record<string, unknown> {
  const value = record[key];
  if (!isRecord(value)) malformed();
  return value;
}

function arrayValue(record: Record<string, unknown>, key: string): unknown[] {
  const value = record[key];
  if (!Array.isArray(value)) malformed();
  return value;
}

function decision(value: unknown): DecisionSummary {
  if (!isRecord(value)) malformed();
  return {
    decisionReference: stringValue(value, "decisionReference"),
    correlationId: stringValue(value, "correlationId"),
    eventType: stringValue(value, "eventType") as DecisionSummary["eventType"],
    outcome: stringValue(value, "outcome") as DecisionSummary["outcome"],
    riskScore: integerValue(value, "riskScore"),
    riskBand: stringValue(value, "riskBand") as DecisionSummary["riskBand"],
    policyKey: stringValue(value, "policyKey"),
    policyVersion: stringValue(value, "policyVersion"),
    decidedAt: stringValue(value, "decidedAt"),
    degraded: booleanValue(value, "degraded"),
    simulated: booleanValue(value, "simulated"),
    provenanceAvailable: booleanValue(value, "provenanceAvailable"),
  };
}

function parseDetail(value: unknown): DecisionInvestigationDetail {
  if (!isRecord(value)) malformed();
  const signal = recordValue(value, "signalProvenance");
  const policy = recordValue(value, "policyProvenance");
  const execution = recordValue(value, "executionProvenance");
  const sections = recordValue(value, "sections");

  return {
    decision: decision(value.decision),
    maskedSubjectReference: stringValue(value, "maskedSubjectReference"),
    reasons: arrayValue(value, "reasons").map((item) => {
      if (!isRecord(item)) malformed();
      return {
        code: stringValue(item, "code"),
        contribution: integerValue(item, "contribution"),
        ordinal: integerValue(item, "ordinal"),
      };
    }),
    signalProvenance: {
      provider: nullableString(signal, "provider"),
      observedAt: nullableString(signal, "observedAt"),
      confidence: nullableString(signal, "confidence"),
      schemaVersion: nullableString(signal, "schemaVersion"),
      state: stringValue(signal, "state") as DecisionInvestigationDetail["signalProvenance"]["state"],
      simulated: booleanValue(signal, "simulated"),
      integrityAvailable: booleanValue(signal, "integrityAvailable"),
    },
    policyProvenance: {
      policyKey: stringValue(policy, "policyKey"),
      policyVersion: stringValue(policy, "policyVersion"),
      routingReason: stringValue(policy, "routingReason"),
      rolloutCohortBucket: nullableInteger(policy, "rolloutCohortBucket"),
      rolloutCandidateVersion: nullableString(policy, "rolloutCandidateVersion"),
      rolloutCandidateSelected: nullableBoolean(policy, "rolloutCandidateSelected"),
    },
    executionProvenance: {
      algorithmVersion: stringValue(execution, "algorithmVersion"),
      normalizedInputSchemaVersion: nullableString(execution, "normalizedInputSchemaVersion"),
      reasonCatalogVersion: nullableString(execution, "reasonCatalogVersion"),
      decisionEngineVersion: nullableString(execution, "decisionEngineVersion"),
      applicationCommitSha: nullableString(execution, "applicationCommitSha"),
      canonicalInputHashAvailable: booleanValue(execution, "canonicalInputHashAvailable"),
      auditRecordHashAvailable: booleanValue(execution, "auditRecordHashAvailable"),
    },
    challenges: arrayValue(value, "challenges").map((item) => {
      if (!isRecord(item)) malformed();
      return {
        reference: stringValue(item, "reference"),
        challengeType: stringValue(item, "challengeType"),
        purpose: stringValue(item, "purpose"),
        status: stringValue(item, "status"),
        createdAt: stringValue(item, "createdAt"),
        expiresAt: stringValue(item, "expiresAt"),
        consumedAt: nullableString(item, "consumedAt"),
      };
    }),
    recovery:
      value.recovery === null
        ? null
        : (() => {
            const item = recordValue(value, "recovery");
            return {
              reference: stringValue(item, "reference"),
              directive: stringValue(item, "directive"),
              status: stringValue(item, "status"),
              issuedAt: stringValue(item, "issuedAt"),
              expiresAt: stringValue(item, "expiresAt"),
              consumedAt: nullableString(item, "consumedAt"),
            };
          })(),
    outboxEvents: arrayValue(value, "outboxEvents").map((item) => {
      if (!isRecord(item)) malformed();
      return {
        reference: stringValue(item, "reference"),
        eventType: stringValue(item, "eventType"),
        status: stringValue(item, "status"),
        occurredAt: stringValue(item, "occurredAt"),
        publishedAt: nullableString(item, "publishedAt"),
        deadLetteredAt: nullableString(item, "deadLetteredAt"),
        attemptCount: integerValue(item, "attemptCount"),
      };
    }),
    timeline: arrayValue(value, "timeline").map((item) => {
      if (!isRecord(item)) malformed();
      return {
        reference: stringValue(item, "reference"),
        kind: stringValue(item, "kind"),
        status: stringValue(item, "status"),
        occurredAt: stringValue(item, "occurredAt"),
      };
    }),
    sections: {
      challenge: stringValue(sections, "challenge") as DecisionInvestigationDetail["sections"]["challenge"],
      recovery: stringValue(sections, "recovery") as DecisionInvestigationDetail["sections"]["recovery"],
      outbox: stringValue(sections, "outbox") as DecisionInvestigationDetail["sections"]["outbox"],
    },
    partial: booleanValue(value, "partial"),
    source: value.source === "fixtures" ? "fixtures" : value.source === "live" ? "live" : malformed(),
  };
}

async function safeProblem(response: Response): Promise<DecisionTimelineBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new DecisionTimelineBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed problem details.
  }
  return new DecisionTimelineBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function investigateDecisionThroughBff(
  decisionReference: string,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<DecisionInvestigationDetail> {
  const response = await (options.fetchImplementation ?? fetch)(ENDPOINT, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify({ decisionReference }),
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });

  if (!response.ok) throw await safeProblem(response);
  return parseDetail((await response.json()) as unknown);
}
