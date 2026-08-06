import type { DecisionReplayComparison, DecisionReplaySide } from "./types";

const ENDPOINT = "/api/bff/decision-replay";

export class DecisionReplayBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Decision replay failed.");
    this.name = "DecisionReplayBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new DecisionReplayBrowserError("MALFORMED_RESPONSE", 502, false);
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

function side(value: unknown): DecisionReplaySide {
  if (!isRecord(value)) malformed();
  return {
    outcome: stringValue(value, "outcome"),
    riskScore: integerValue(value, "riskScore"),
    riskBand: stringValue(value, "riskBand"),
    reasons: arrayValue(value, "reasons").map((item) => {
      if (!isRecord(item)) malformed();
      return {
        code: stringValue(item, "code"),
        contribution: integerValue(item, "contribution"),
      };
    }),
  };
}

function parseComparison(value: unknown): DecisionReplayComparison {
  if (!isRecord(value)) malformed();
  return {
    decisionReference: stringValue(value, "decisionReference"),
    maskedSubjectReference: stringValue(value, "maskedSubjectReference"),
    matches: booleanValue(value, "matches"),
    original: side(recordValue(value, "original")),
    replayed: side(recordValue(value, "replayed")),
    policyKey: stringValue(value, "policyKey"),
    policyVersion: stringValue(value, "policyVersion"),
    algorithmVersion: stringValue(value, "algorithmVersion"),
    normalizedInputSchemaVersion: nullableString(value, "normalizedInputSchemaVersion"),
    reasonCatalogVersion: stringValue(value, "reasonCatalogVersion"),
    decisionEngineVersion: stringValue(value, "decisionEngineVersion"),
    mismatches: arrayValue(value, "mismatches").map((item) => {
      if (typeof item !== "string") malformed();
      return item;
    }),
    source: value.source === "fixtures" ? "fixtures" : value.source === "live" ? "live" : malformed(),
  };
}

async function safeProblem(response: Response): Promise<DecisionReplayBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new DecisionReplayBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed problem details.
  }
  return new DecisionReplayBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function replayDecisionThroughBff(
  decisionReference: string,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<DecisionReplayComparison> {
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
  return parseComparison((await response.json()) as unknown);
}
