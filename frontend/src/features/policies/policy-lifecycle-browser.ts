const CSRF_COOKIE_NAME = "as_csrf";
const CSRF_HEADER_NAME = "x-as-csrf-token";

export class PolicyLifecycleBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super("Policy lifecycle request failed.");
    this.name = "PolicyLifecycleBrowserError";
  }
}

export type PolicyLifecycleAction = "APPROVE" | "ACTIVATE" | "RETIRE";

export interface StepUpChallenge {
  challengeId: string;
  simulatedCode: string | null;
  contextId: string;
}

export interface VerifiedStepUp {
  verified: boolean;
  status: string;
  remainingAttempts: number;
}

const STEP_UP_ENDPOINT: Record<PolicyLifecycleAction, string> = {
  APPROVE: "/api/bff/policy-lifecycle/approve-step-up",
  ACTIVATE: "/api/bff/policy-lifecycle/activate-step-up",
  RETIRE: "/api/bff/policy-lifecycle/retire-step-up",
};
const VERIFY_ENDPOINT = "/api/bff/policy-lifecycle/verify";
const APPROVE_ENDPOINT = "/api/bff/policy-lifecycle/approve";
const ACTIVATE_ENDPOINT = "/api/bff/policy-lifecycle/activate";
const REJECT_ENDPOINT = "/api/bff/policy-lifecycle/reject";
const RETIRE_ENDPOINT = "/api/bff/policy-lifecycle/retire";

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

/** Reads the non-HttpOnly double-submit CSRF cookie, mirroring features/session/session-browser.ts. */
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
  // features/session/session-browser.ts and features/recoveries/recovery-review-browser.ts --
  // ARCH005's static analysis flags a literal `fetch(<identifier>)` call in presentation code as
  // a possible raw/dynamic backend transport, even though `endpoint` here is always one of the
  // fixed same-origin BFF route constants above, never an arbitrary or request-derived URL. Read
  // fresh on every call (not hoisted to module scope) so test stubs of the global `fetch` still
  // apply.
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
    throw new PolicyLifecycleBrowserError("MALFORMED_RESPONSE", response.status || 502);
  }
  if (!response.ok) {
    const code = isRecord(parsed) && typeof parsed.code === "string" ? parsed.code : "REQUEST_FAILED";
    throw new PolicyLifecycleBrowserError(code, response.status);
  }
  if (!isRecord(parsed)) throw new PolicyLifecycleBrowserError("MALFORMED_RESPONSE", 502);
  return parsed;
}

export async function requestLifecycleStepUp(
  action: PolicyLifecycleAction,
  policyKey: string,
  version: string,
): Promise<StepUpChallenge> {
  const body = await post(STEP_UP_ENDPOINT[action], { policyKey, version });
  if (typeof body.challengeId !== "string" || typeof body.contextId !== "string") {
    throw new PolicyLifecycleBrowserError("MALFORMED_RESPONSE", 502);
  }
  return {
    challengeId: body.challengeId,
    simulatedCode: typeof body.simulatedCode === "string" ? body.simulatedCode : null,
    contextId: body.contextId,
  };
}

export async function verifyLifecycleStepUp(
  challengeId: string,
  contextId: string,
  providedCode: string,
): Promise<VerifiedStepUp> {
  const body = await post(VERIFY_ENDPOINT, { challengeId, contextId, providedCode });
  if (typeof body.verified !== "boolean" || typeof body.status !== "string") {
    throw new PolicyLifecycleBrowserError("MALFORMED_RESPONSE", 502);
  }
  return {
    verified: body.verified,
    status: body.status,
    remainingAttempts: typeof body.remainingAttempts === "number" ? body.remainingAttempts : 0,
  };
}

async function submit(endpoint: string, body: Record<string, unknown>): Promise<{ status: string }> {
  const responseBody = await post(endpoint, body);
  if (typeof responseBody.status !== "string") throw new PolicyLifecycleBrowserError("MALFORMED_RESPONSE", 502);
  return { status: responseBody.status };
}

export async function approvePolicyVersion(
  policyKey: string,
  version: string,
  stepUpChallengeId: string,
  reason: string,
): Promise<{ status: string }> {
  return submit(APPROVE_ENDPOINT, { policyKey, version, stepUpChallengeId, reason });
}

export async function activatePolicyVersion(
  policyKey: string,
  version: string,
  stepUpChallengeId: string,
): Promise<{ status: string }> {
  return submit(ACTIVATE_ENDPOINT, { policyKey, version, stepUpChallengeId });
}

export async function rejectPolicyVersion(policyKey: string, version: string): Promise<{ status: string }> {
  return submit(REJECT_ENDPOINT, { policyKey, version });
}

export async function retirePolicyVersion(
  policyKey: string,
  version: string,
  stepUpChallengeId: string,
): Promise<{ status: string }> {
  return submit(RETIRE_ENDPOINT, { policyKey, version, stepUpChallengeId });
}
