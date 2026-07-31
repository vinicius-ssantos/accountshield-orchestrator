import type {
  PolicyAnalysis,
  PolicyDiagnostic,
  PolicyDivergentDecision,
  PolicyGovernance,
  PolicyImpactSummary,
  PolicyInvestigationDetail,
  PolicyReasonEvidence,
  PolicyRolloutSummary,
  PolicyRoutingScopeEntry,
  PolicySegmentImpact,
  PolicyVersionSummary,
} from "./types";

const ENDPOINT = "/api/bff/policy-investigation";

export class PolicyInvestigationBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Policy investigation failed.");
    this.name = "PolicyInvestigationBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new PolicyInvestigationBrowserError("MALFORMED_RESPONSE", 502, false);
}

function stringValue(record: Record<string, unknown>, key: string): string {
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

function booleanValue(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") malformed();
  return value;
}

function nullableInteger(record: Record<string, unknown>, key: string): number | null {
  const value = record[key];
  if (value === null || value === undefined) return null;
  if (!Number.isInteger(value)) malformed();
  return value as number;
}

function integerValue(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (!Number.isInteger(value)) malformed();
  return value as number;
}

function numberValue(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (typeof value !== "number" || !Number.isFinite(value)) malformed();
  return value as number;
}

function recordValue(record: Record<string, unknown>, key: string): Record<string, unknown> {
  const value = record[key];
  if (!isRecord(value)) malformed();
  return value;
}

function nullableRecordValue(record: Record<string, unknown>, key: string): Record<string, unknown> | null {
  const value = record[key];
  if (value === null || value === undefined) return null;
  if (!isRecord(value)) malformed();
  return value;
}

function arrayValue(record: Record<string, unknown>, key: string): unknown[] {
  const value = record[key];
  if (!Array.isArray(value)) malformed();
  return value;
}

function stringArray(record: Record<string, unknown>, key: string): readonly string[] {
  return arrayValue(record, key).map((item) => {
    if (typeof item !== "string") malformed();
    return item;
  });
}

function reasonEvidence(value: unknown): PolicyReasonEvidence {
  if (!isRecord(value)) malformed();
  return {
    code: stringValue(value, "code"),
    contribution: integerValue(value, "contribution"),
  };
}

function diagnostic(value: unknown): PolicyDiagnostic {
  if (!isRecord(value)) malformed();
  return {
    code: stringValue(value, "code"),
    severity: stringValue(value, "severity") as PolicyDiagnostic["severity"],
    path: stringValue(value, "path"),
    message: stringValue(value, "message"),
  };
}

function analysis(value: Record<string, unknown> | null): PolicyAnalysis | null {
  if (value === null) return null;
  return {
    analyzerVersion: stringValue(value, "analyzerVersion"),
    diagnostics: arrayValue(value, "diagnostics").map(diagnostic),
  };
}

function governance(value: Record<string, unknown> | null): PolicyGovernance | null {
  if (value === null) return null;
  return {
    createdBy: nullableString(value, "createdBy"),
    validatedBy: nullableString(value, "validatedBy"),
    validatedAt: nullableString(value, "validatedAt"),
    approvedBy: nullableString(value, "approvedBy"),
    approvedAt: nullableString(value, "approvedAt"),
    approvalReason: nullableString(value, "approvalReason"),
  };
}

function versionSummary(value: unknown): PolicyVersionSummary {
  if (!isRecord(value)) malformed();
  return {
    id: stringValue(value, "id"),
    policyKey: stringValue(value, "policyKey"),
    version: stringValue(value, "version"),
    status: stringValue(value, "status") as PolicyVersionSummary["status"],
    allowMaxScore: nullableInteger(value, "allowMaxScore"),
    stepUpMaxScore: nullableInteger(value, "stepUpMaxScore"),
    recoveryMaxScore: nullableInteger(value, "recoveryMaxScore"),
    createdAt: stringValue(value, "createdAt"),
    activatedAt: nullableString(value, "activatedAt"),
    analysis: analysis(nullableRecordValue(value, "analysis")),
    governance: governance(nullableRecordValue(value, "governance")),
  };
}

function routingScopeEntry(value: unknown): PolicyRoutingScopeEntry {
  if (!isRecord(value)) malformed();
  return {
    clientId: stringValue(value, "clientId"),
    eventType: stringValue(value, "eventType"),
  };
}

function rolloutSummary(value: Record<string, unknown> | null): PolicyRolloutSummary | null {
  if (value === null) return null;
  return {
    candidateVersion: stringValue(value, "candidateVersion"),
    rolloutPercentage: integerValue(value, "rolloutPercentage"),
    status: stringValue(value, "status") as PolicyRolloutSummary["status"],
    startedAt: stringValue(value, "startedAt"),
    startedBy: stringValue(value, "startedBy"),
    updatedAt: stringValue(value, "updatedAt"),
    rolledBackAt: nullableString(value, "rolledBackAt"),
    rolledBackBy: nullableString(value, "rolledBackBy"),
  };
}

function segmentImpactMap(value: unknown): Record<string, PolicySegmentImpact> {
  if (!isRecord(value)) malformed();
  const result: Record<string, PolicySegmentImpact> = {};
  for (const [segment, entry] of Object.entries(value)) {
    if (!isRecord(entry)) malformed();
    result[segment] = {
      segment: stringValue(entry, "segment"),
      totalDecisions: integerValue(entry, "totalDecisions"),
      divergentDecisions: integerValue(entry, "divergentDecisions"),
    };
  }
  return result;
}

function transitionMatrix(value: unknown): Record<string, Record<string, number>> {
  if (!isRecord(value)) malformed();
  const result: Record<string, Record<string, number>> = {};
  for (const [from, row] of Object.entries(value)) {
    if (!isRecord(row)) malformed();
    const parsedRow: Record<string, number> = {};
    for (const [to, count] of Object.entries(row)) {
      if (!Number.isInteger(count)) malformed();
      parsedRow[to] = count as number;
    }
    result[from] = parsedRow;
  }
  return result;
}

function divergentDecision(value: unknown): PolicyDivergentDecision {
  if (!isRecord(value)) malformed();
  return {
    maskedProtectionRequestReference: stringValue(value, "maskedProtectionRequestReference"),
    redactedAccountReference: stringValue(value, "redactedAccountReference"),
    originalOutcome: stringValue(value, "originalOutcome"),
    candidateOutcome: stringValue(value, "candidateOutcome"),
    riskScore: integerValue(value, "riskScore"),
    originalReasons: arrayValue(value, "originalReasons").map(reasonEvidence),
  };
}

function impactSummary(value: Record<string, unknown> | null): PolicyImpactSummary | null {
  if (value === null) return null;
  return {
    candidatePolicyVersion: stringValue(value, "candidatePolicyVersion"),
    originalPolicyVersionsObserved: stringArray(value, "originalPolicyVersionsObserved"),
    algorithmVersionsObserved: stringArray(value, "algorithmVersionsObserved"),
    totalDecisions: integerValue(value, "totalDecisions"),
    divergentDecisionsCount: integerValue(value, "divergentDecisionsCount"),
    divergencePercentage: numberValue(value, "divergencePercentage"),
    maxDivergencePercentageThreshold: numberValue(value, "maxDivergencePercentageThreshold"),
    exceedsDivergenceThreshold: booleanValue(value, "exceedsDivergenceThreshold"),
    transitionMatrix: transitionMatrix(recordValue(value, "transitionMatrix")),
    impactByEventType: segmentImpactMap(recordValue(value, "impactByEventType")),
    impactByRiskBand: segmentImpactMap(recordValue(value, "impactByRiskBand")),
    divergentDecisions: arrayValue(value, "divergentDecisions").map(divergentDecision),
  };
}

function parseDetail(value: unknown): PolicyInvestigationDetail {
  if (!isRecord(value)) malformed();

  const impactAvailability = stringValue(
    value,
    "impactAvailability",
  ) as PolicyInvestigationDetail["impactAvailability"];

  return {
    policyKey: stringValue(value, "policyKey"),
    versions: arrayValue(value, "versions").map(versionSummary),
    routingScope: arrayValue(value, "routingScope").map(routingScopeEntry),
    activeRollout: rolloutSummary(nullableRecordValue(value, "activeRollout")),
    impactAnalysis: impactSummary(nullableRecordValue(value, "impactAnalysis")),
    impactAvailability,
    source: value.source === "fixtures" ? "fixtures" : value.source === "live" ? "live" : malformed(),
  };
}

async function safeProblem(response: Response): Promise<PolicyInvestigationBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new PolicyInvestigationBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed problem details.
  }
  return new PolicyInvestigationBrowserError(
    "REQUEST_FAILED",
    response.status,
    response.status >= 500,
  );
}

export async function investigatePolicyThroughBff(
  policyKey: string,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<PolicyInvestigationDetail> {
  const response = await (options.fetchImplementation ?? fetch)(ENDPOINT, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify({ policyKey }),
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });

  if (!response.ok) throw await safeProblem(response);
  return parseDetail((await response.json()) as unknown);
}
