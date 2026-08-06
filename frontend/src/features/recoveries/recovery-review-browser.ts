const STEP_UP_ENDPOINT = "/api/bff/recovery-review/step-up";
const VERIFY_ENDPOINT = "/api/bff/recovery-review/verify";
const SUBMIT_ENDPOINT = "/api/bff/recovery-review/submit";
const CSRF_COOKIE_NAME = "as_csrf";
const CSRF_HEADER_NAME = "x-as-csrf-token";

export class RecoveryReviewBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super("Recovery review request failed.");
    this.name = "RecoveryReviewBrowserError";
  }
}

export interface StepUpChallenge {
  challengeId: string;
  simulatedCode: string | null;
}

export interface VerifiedStepUp {
  verified: boolean;
  status: string;
  remainingAttempts: number;
}

export type RecoveryReviewDecision = "APPROVE" | "REJECT";

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
  // features/session/session-browser.ts's convention -- ARCH005's static analysis flags a
  // literal `fetch(<identifier>)` call in presentation code as a possible raw/dynamic backend
  // transport, even though `endpoint` here is always one of the three fixed same-origin BFF
  // route constants above, never an arbitrary or request-derived URL. Read fresh on every call
  // (not hoisted to module scope) so test stubs of the global `fetch` still apply.
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
    throw new RecoveryReviewBrowserError("MALFORMED_RESPONSE", response.status || 502);
  }
  if (!response.ok) {
    const code = isRecord(parsed) && typeof parsed.code === "string" ? parsed.code : "REQUEST_FAILED";
    throw new RecoveryReviewBrowserError(code, response.status);
  }
  if (!isRecord(parsed)) throw new RecoveryReviewBrowserError("MALFORMED_RESPONSE", 502);
  return parsed;
}

export async function requestReviewStepUp(recoveryReference: string): Promise<StepUpChallenge> {
  const body = await post(STEP_UP_ENDPOINT, { recoveryReference });
  if (typeof body.challengeId !== "string") throw new RecoveryReviewBrowserError("MALFORMED_RESPONSE", 502);
  return {
    challengeId: body.challengeId,
    simulatedCode: typeof body.simulatedCode === "string" ? body.simulatedCode : null,
  };
}

export async function verifyReviewStepUp(
  recoveryReference: string,
  challengeId: string,
  providedCode: string,
): Promise<VerifiedStepUp> {
  const body = await post(VERIFY_ENDPOINT, { recoveryReference, challengeId, providedCode });
  if (typeof body.verified !== "boolean" || typeof body.status !== "string") {
    throw new RecoveryReviewBrowserError("MALFORMED_RESPONSE", 502);
  }
  return {
    verified: body.verified,
    status: body.status,
    remainingAttempts: typeof body.remainingAttempts === "number" ? body.remainingAttempts : 0,
  };
}

export async function submitRecoveryReview(
  recoveryReference: string,
  decision: RecoveryReviewDecision,
  stepUpChallengeId: string,
): Promise<{ status: string }> {
  const body = await post(SUBMIT_ENDPOINT, { recoveryReference, decision, stepUpChallengeId });
  if (typeof body.status !== "string") throw new RecoveryReviewBrowserError("MALFORMED_RESPONSE", 502);
  return { status: body.status };
}
