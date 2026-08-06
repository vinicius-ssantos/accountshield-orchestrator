import type {
  DecisionSearchCriteria,
  DecisionSearchPage,
  DecisionSummary,
} from "./types";

const ENDPOINT = "/api/bff/decision-search";

export class DecisionSearchBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Decision search failed.");
    this.name = "DecisionSearchBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function parseDecision(value: unknown): DecisionSummary | null {
  if (!isRecord(value)) return null;
  const requiredStrings = [
    "decisionReference",
    "correlationId",
    "eventType",
    "outcome",
    "riskBand",
    "policyKey",
    "policyVersion",
    "decidedAt",
  ] as const;
  if (requiredStrings.some((key) => typeof value[key] !== "string")) return null;
  if (
    !Number.isInteger(value.riskScore) ||
    typeof value.degraded !== "boolean" ||
    typeof value.simulated !== "boolean" ||
    typeof value.provenanceAvailable !== "boolean"
  ) {
    return null;
  }

  return value as unknown as DecisionSummary;
}

function parsePage(value: unknown): DecisionSearchPage {
  if (!isRecord(value) || !Array.isArray(value.decisions)) {
    throw new DecisionSearchBrowserError("MALFORMED_RESPONSE", 502, false);
  }
  const decisions = value.decisions.map(parseDecision);
  if (
    decisions.some((decision) => decision === null) ||
    !Number.isInteger(value.pageSize) ||
    typeof value.hasMore !== "boolean" ||
    (value.nextCursor !== undefined &&
      value.nextCursor !== null &&
      typeof value.nextCursor !== "string") ||
    (value.source !== "fixtures" && value.source !== "live") ||
    typeof value.partial !== "boolean"
  ) {
    throw new DecisionSearchBrowserError("MALFORMED_RESPONSE", 502, false);
  }

  return {
    decisions: decisions as DecisionSummary[],
    nextCursor: typeof value.nextCursor === "string" ? value.nextCursor : undefined,
    pageSize: value.pageSize as number,
    hasMore: value.hasMore,
    source: value.source,
    partial: value.partial,
  };
}

async function safeProblem(response: Response): Promise<DecisionSearchBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new DecisionSearchBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed upstream/browser-facing details.
  }
  return new DecisionSearchBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function searchDecisionsThroughBff(
  criteria: DecisionSearchCriteria,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<DecisionSearchPage> {
  const fetchImplementation = options.fetchImplementation ?? fetch;
  const response = await fetchImplementation(ENDPOINT, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify(criteria),
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });

  if (!response.ok) throw await safeProblem(response);
  return parsePage((await response.json()) as unknown);
}
