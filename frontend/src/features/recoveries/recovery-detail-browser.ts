import type { RecoveryInvestigationDetail, RecoverySummary } from "./types";

const ENDPOINT = "/api/bff/recovery-detail";

export class RecoveryDetailBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Recovery investigation failed.");
    this.name = "RecoveryDetailBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new RecoveryDetailBrowserError("MALFORMED_RESPONSE", 502, false);
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

function integerValue(record: Record<string, unknown>, key: string): number {
  const value = record[key];
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

function summary(value: unknown): RecoverySummary {
  if (!isRecord(value)) malformed();
  return {
    recoveryReference: stringValue(value, "recoveryReference"),
    maskedSubjectReference: stringValue(value, "maskedSubjectReference"),
    eventType: stringValue(value, "eventType") as RecoverySummary["eventType"],
    status: stringValue(value, "status") as RecoverySummary["status"],
    terminal: booleanValue(value, "terminal"),
    classification: stringValue(value, "classification") as RecoverySummary["classification"],
    classificationRuleVersion: stringValue(value, "classificationRuleVersion"),
    riskScore: integerValue(value, "riskScore"),
    initiatedAt: stringValue(value, "initiatedAt"),
    updatedAt: stringValue(value, "updatedAt"),
    eligibleAfter: nullableString(value, "eligibleAfter"),
    originatingDecisionReference: stringValue(value, "originatingDecisionReference"),
    reviewState: stringValue(value, "reviewState") as RecoverySummary["reviewState"],
    challengeExpected: booleanValue(value, "challengeExpected"),
  };
}

function parseDetail(value: unknown): RecoveryInvestigationDetail {
  if (!isRecord(value)) malformed();

  return {
    recovery: summary(recordValue(value, "recovery")),
    protectionRequestReference: stringValue(value, "protectionRequestReference"),
    reviewerPresent: booleanValue(value, "reviewerPresent"),
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
    challengeAvailability: stringValue(
      value,
      "challengeAvailability",
    ) as RecoveryInvestigationDetail["challengeAvailability"],
    partial: booleanValue(value, "partial"),
    source: value.source === "fixtures" ? "fixtures" : value.source === "live" ? "live" : malformed(),
  };
}

async function safeProblem(response: Response): Promise<RecoveryDetailBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new RecoveryDetailBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed problem details.
  }
  return new RecoveryDetailBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function investigateRecoveryThroughBff(
  recoveryReference: string,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<RecoveryInvestigationDetail> {
  const response = await (options.fetchImplementation ?? fetch)(ENDPOINT, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify({ recoveryReference }),
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });

  if (!response.ok) throw await safeProblem(response);
  return parseDetail((await response.json()) as unknown);
}
