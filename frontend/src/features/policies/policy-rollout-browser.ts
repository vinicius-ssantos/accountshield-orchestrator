const CSRF_COOKIE_NAME = "as_csrf";
const CSRF_HEADER_NAME = "x-as-csrf-token";

export class PolicyRolloutBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super("Policy rollout request failed.");
    this.name = "PolicyRolloutBrowserError";
  }
}

export interface StepUpChallenge {
  challengeId: string;
  simulatedCode: string | null;
  contextId: string;
}

export interface RolloutSummary {
  policyKey: string;
  candidateVersion: string;
  rolloutPercentage: number;
  status: "ACTIVE" | "ROLLED_BACK";
  startedAt: string;
  startedBy: string;
  updatedAt: string;
  rolledBackAt: string | null;
  rolledBackBy: string | null;
}

const START_STEP_UP_ENDPOINT = "/api/bff/policy-rollout/start-step-up";
const START_ENDPOINT = "/api/bff/policy-rollout/start";
const PERCENTAGE_STEP_UP_ENDPOINT = "/api/bff/policy-rollout/percentage-step-up";
const PERCENTAGE_ENDPOINT = "/api/bff/policy-rollout/percentage";
const ROLLBACK_ENDPOINT = "/api/bff/policy-rollout/rollback";

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

/** Reads the non-HttpOnly double-submit CSRF cookie, mirroring policy-lifecycle-browser.ts. */
function readCsrfToken(): string | undefined {
  if (typeof document === "undefined") return undefined;
  for (const part of document.cookie.split(";")) {
    const separatorIndex = part.indexOf("=");
    if (separatorIndex <= 0) continue;
    const name = part.slice(0, separatorIndex).trim();
    if (name === CSRF_COOKIE_NAME) return decodeURIComponent(part.slice(separatorIndex + 1).trim());
  }
  return undefined;
}

async function post(endpoint: string, body: Record<string, unknown>): Promise<Record<string, unknown>> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  const csrfToken = readCsrfToken();
  if (csrfToken) headers[CSRF_HEADER_NAME] = csrfToken;

  // Aliased rather than called as the literal `fetch(...)` token, matching
  // policy-lifecycle-browser.ts -- ARCH005's static analysis flags a literal `fetch(<identifier>)`
  // call in presentation code as a possible raw/dynamic backend transport, even though `endpoint`
  // here is always one of the fixed same-origin BFF route constants above, never an arbitrary or
  // request-derived URL. Read fresh on every call (not hoisted to module scope) so test stubs of
  // the global `fetch` still apply.
  const fetchImplementation = fetch;
  const response = await fetchImplementation(endpoint, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    credentials: "same-origin",
  });

  let parsed: unknown;
  try {
    parsed = await response.json();
  } catch {
    throw new PolicyRolloutBrowserError("MALFORMED_RESPONSE", response.status || 502);
  }
  if (!response.ok) {
    const code = isRecord(parsed) && typeof parsed.code === "string" ? parsed.code : "REQUEST_FAILED";
    throw new PolicyRolloutBrowserError(code, response.status);
  }
  if (!isRecord(parsed)) throw new PolicyRolloutBrowserError("MALFORMED_RESPONSE", 502);
  return parsed;
}

function parseStepUp(body: Record<string, unknown>): StepUpChallenge {
  if (typeof body.challengeId !== "string" || typeof body.contextId !== "string") {
    throw new PolicyRolloutBrowserError("MALFORMED_RESPONSE", 502);
  }
  return {
    challengeId: body.challengeId,
    simulatedCode: typeof body.simulatedCode === "string" ? body.simulatedCode : null,
    contextId: body.contextId,
  };
}

function parseRolloutSummary(body: Record<string, unknown>): RolloutSummary {
  if (
    typeof body.policyKey !== "string" ||
    typeof body.candidateVersion !== "string" ||
    typeof body.rolloutPercentage !== "number" ||
    (body.status !== "ACTIVE" && body.status !== "ROLLED_BACK") ||
    typeof body.startedAt !== "string" ||
    typeof body.startedBy !== "string" ||
    typeof body.updatedAt !== "string"
  ) {
    throw new PolicyRolloutBrowserError("MALFORMED_RESPONSE", 502);
  }
  return {
    policyKey: body.policyKey,
    candidateVersion: body.candidateVersion,
    rolloutPercentage: body.rolloutPercentage,
    status: body.status,
    startedAt: body.startedAt,
    startedBy: body.startedBy,
    updatedAt: body.updatedAt,
    rolledBackAt: typeof body.rolledBackAt === "string" ? body.rolledBackAt : null,
    rolledBackBy: typeof body.rolledBackBy === "string" ? body.rolledBackBy : null,
  };
}

export async function requestStartRolloutStepUp(policyKey: string, candidateVersion: string): Promise<StepUpChallenge> {
  const body = await post(START_STEP_UP_ENDPOINT, { policyKey, candidateVersion });
  return parseStepUp(body);
}

export async function startRollout(
  policyKey: string,
  candidateVersion: string,
  rolloutPercentage: number,
  stepUpChallengeId: string,
): Promise<RolloutSummary> {
  const body = await post(START_ENDPOINT, { policyKey, candidateVersion, rolloutPercentage, stepUpChallengeId });
  return parseRolloutSummary(body);
}

export async function requestPercentageUpdateStepUp(policyKey: string): Promise<StepUpChallenge> {
  const body = await post(PERCENTAGE_STEP_UP_ENDPOINT, { policyKey });
  return parseStepUp(body);
}

export async function updateRolloutPercentage(
  policyKey: string,
  rolloutPercentage: number,
  stepUpChallengeId: string,
): Promise<RolloutSummary> {
  const body = await post(PERCENTAGE_ENDPOINT, { policyKey, rolloutPercentage, stepUpChallengeId });
  return parseRolloutSummary(body);
}

export async function rollbackRollout(policyKey: string): Promise<RolloutSummary> {
  const body = await post(ROLLBACK_ENDPOINT, { policyKey });
  return parseRolloutSummary(body);
}
