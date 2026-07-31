import type { PolicyDirectoryPage, PolicyDirectorySummary } from "./types";

const ENDPOINT = "/api/bff/policy-directory";

export class PolicyDirectoryBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super("Policy directory search failed.");
    this.name = "PolicyDirectoryBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function parseSummary(value: unknown): PolicyDirectorySummary | null {
  if (!isRecord(value)) return null;
  if (typeof value.policyKey !== "string" || !Number.isInteger(value.totalVersions)) return null;
  if (typeof value.hasActiveRollout !== "boolean") return null;
  if (value.activeVersion !== null && typeof value.activeVersion !== "string") return null;
  if (
    value.activeVersionActivatedAt !== null &&
    typeof value.activeVersionActivatedAt !== "string"
  ) {
    return null;
  }
  return value as unknown as PolicyDirectorySummary;
}

function parsePage(value: unknown): PolicyDirectoryPage {
  if (!isRecord(value) || !Array.isArray(value.policies)) {
    throw new PolicyDirectoryBrowserError("MALFORMED_RESPONSE", 502, false);
  }
  const policies = value.policies.map(parseSummary);
  if (
    policies.some((item) => item === null) ||
    (value.source !== "fixtures" && value.source !== "live")
  ) {
    throw new PolicyDirectoryBrowserError("MALFORMED_RESPONSE", 502, false);
  }

  return {
    policies: policies as PolicyDirectorySummary[],
    source: value.source,
  };
}

async function safeProblem(response: Response): Promise<PolicyDirectoryBrowserError> {
  try {
    const value = (await response.json()) as unknown;
    if (isRecord(value)) {
      return new PolicyDirectoryBrowserError(
        typeof value.code === "string" ? value.code : "REQUEST_FAILED",
        response.status,
        value.retryable === true,
      );
    }
  } catch {
    // Deliberately discard malformed upstream/browser-facing details.
  }
  return new PolicyDirectoryBrowserError("REQUEST_FAILED", response.status, response.status >= 500);
}

export async function searchPoliciesThroughBff(
  options: {
    signal?: AbortSignal;
    fetchImplementation?: typeof fetch;
  } = {},
): Promise<PolicyDirectoryPage> {
  const fetchImplementation = options.fetchImplementation ?? fetch;
  const response = await fetchImplementation(ENDPOINT, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify({}),
    cache: "no-store",
    credentials: "same-origin",
    signal: options.signal,
  });

  if (!response.ok) throw await safeProblem(response);
  return parsePage((await response.json()) as unknown);
}
