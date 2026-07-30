import type {
  RecoverySearchCriteria,
  RecoverySearchPage,
  RecoverySummary,
} from "./types";

const ENDPOINT = "/api/bff/recovery-search";

export class RecoverySearchBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Recovery search failed.");
    this.name = "RecoverySearchBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function parseSummary(value: unknown): RecoverySummary | null {
  if (!isRecord(value)) return null;
  const requiredStrings = [
    "recoveryReference",
    "maskedSubjectReference",
    "eventType",
    "status",
    "classification",
    "classificationRuleVersion",
    "initiatedAt",
    "updatedAt",
    "originatingDecisionReference",
    "reviewState",
  ] as const;
  if (requiredStrings.some((key) => typeof value[key] !== "string")) return null;
  if (
    !Number.isInteger(value.riskScore) ||
    typeof value.terminal !== "boolean" ||
    typeof value.challengeExpected !== "boolean" ||
    (value.eligibleAfter !== null && typeof value.eligibleAfter !== "string")
  ) {
    return null;
  }

  return value as unknown as RecoverySummary;
}

function parsePage(value: unknown): RecoverySearchPage {
  if (!isRecord(value) || !Array.isArray(value.recoveries)) {
    throw new RecoverySearchBrowserError("MALFORMED_RESPONSE", 502, false);
  }
  const recoveries = value.recoveries.map(parseSummary);
  if (
    recoveries.some((item) => item === null) ||
    !Number.isInteger(value.pageSize) ||
    typeof value.hasMore !== "boolean" ||
    (value.nextCursor !== undefined &&
      value.nextCursor !== null &&
      typeof value.nextCursor !== "string") ||
    (value.source !== "fixtures" && value.source !== "live") ||
    typeof value.partial !== "boolean"
  ) {
    throw new RecoverySearchBrowserError("MALFORMED_RESPONSE", 502, false);
  }

  return {
    recoveries: recoveries as RecoverySummary[],
    nextCursor: typeof value.nextCursor === "string" ? value.nextCursor : undefined,
    pageSize: value.pageSize as number,
    hasMore: value.hasMore,
    source: value.source,
    partial: value.partial,
  };
}

async function safeProblem(response: Response): Promise<RecoverySearchBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new RecoverySearchBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed upstream/browser-facing details.
  }
  return new RecoverySearchBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function searchRecoveriesThroughBff(
  criteria: RecoverySearchCriteria,
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<RecoverySearchPage> {
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
