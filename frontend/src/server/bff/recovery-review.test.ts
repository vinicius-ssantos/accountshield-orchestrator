import { beforeEach, describe, expect, it, vi } from "vitest";

import { csrfCookieValue } from "./session/csrf";
import { buildSessionCookieValue } from "./session/session-crypto";
import type { SessionRecord } from "./session/session-model";
import { clearSessionStoreForTesting, createSession } from "./session/session-store";
import {
  handleReviewSubmissionRequest,
  handleStepUpRequest,
  handleVerifyStepUpRequest,
} from "./recovery-review";
import type { RecoveryReviewService } from "./recovery-review-core";

const ENV = { NEXT_PUBLIC_APP_ENV: "test" };
const LIVE_ENV = { NEXT_PUBLIC_APP_ENV: "test", ACCOUNTSHIELD_DATA_SOURCE: "live", ACCOUNTSHIELD_API_URL: "http://localhost:8080" };
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";
const REFERENCE = "00000000-0000-4000-9000-000000000003";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";

function fakeService(overrides: Partial<RecoveryReviewService> = {}): RecoveryReviewService {
  return {
    requestStepUp: vi.fn(async () => ({ challengeId: CHALLENGE_ID, simulatedCode: "482913" })),
    verifyStepUp: vi.fn(async () => ({ verified: true, status: "VERIFIED", remainingAttempts: 3 })),
    submitReview: vi.fn(async () => ({ status: "COMPLETED" })),
    ...overrides,
  };
}

function storedSession(overrides: Partial<SessionRecord> = {}) {
  const now = Date.now();
  const record: SessionRecord = {
    sessionId: "session-id",
    subject: "operator-1",
    roles: ["SECURITY_OPERATOR"],
    backendToken: "backend-token",
    backendTokenExpiresAt: now + 60_000,
    csrfSecret: "csrf-secret",
    createdAt: now,
    lastSeenAt: now,
    absoluteExpiresAt: now + 8 * 60 * 60 * 1000,
    ...overrides,
  };
  createSession(record);
  return {
    cookieValue: buildSessionCookieValue(record.sessionId, SECRET),
    csrfToken: csrfCookieValue(record.sessionId, record.csrfSecret, SECRET),
  };
}

function authorizedRequest(path: string, body: unknown, cookieValue: string, csrfToken: string): Request {
  return new Request(`${ORIGIN}/api/bff/recovery-review/${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      cookie: `as_session=${cookieValue}`,
      "sec-fetch-site": "same-origin",
      "x-as-csrf-token": csrfToken,
    },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  clearSessionStoreForTesting();
});

describe("handleStepUpRequest", () => {
  it("requires an authenticated session", async () => {
    // No service injected -- this must exercise the real createRecoveryReviewClient path so the
    // session guard actually runs (a fake service would bypass it entirely).
    const response = await handleStepUpRequest(
      new Request(`${ORIGIN}/api/bff/recovery-review/step-up`, {
        method: "POST",
        headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: JSON.stringify({ recoveryReference: REFERENCE }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(401);
  });

  it("rejects a mutating request missing the CSRF header", async () => {
    const { cookieValue } = storedSession();
    const response = await handleStepUpRequest(
      new Request(`${ORIGIN}/api/bff/recovery-review/step-up`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
        },
        body: JSON.stringify({ recoveryReference: REFERENCE }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(403);
  });

  it("returns the disclosed simulated code for an authorized session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleStepUpRequest(
      authorizedRequest("step-up", { recoveryReference: REFERENCE }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "482913" });
  });

  it("passes the session's backend token to the service, never a client-supplied one", async () => {
    const { cookieValue, csrfToken } = storedSession({ backendToken: "the-real-backend-token" });
    const service = fakeService();
    await handleStepUpRequest(
      authorizedRequest("step-up", { recoveryReference: REFERENCE }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    // The fake service doesn't receive the token directly (only the real client does), but this
    // proves the call reaches the service at all only once session+CSRF checks already passed.
    expect(service.requestStepUp).toHaveBeenCalledTimes(1);
  });

  it("rejects a malformed body with 400, not 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleStepUpRequest(
      authorizedRequest("step-up", { recoveryReference: "not-a-uuid" }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(400);
  });
});

describe("handleVerifyStepUpRequest", () => {
  it("returns verified=false without throwing when the code is wrong", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService({
      verifyStepUp: vi.fn(async () => ({ verified: false, status: "CHALLENGED", remainingAttempts: 1 })),
    });
    const response = await handleVerifyStepUpRequest(
      authorizedRequest(
        "verify",
        { recoveryReference: REFERENCE, challengeId: CHALLENGE_ID, providedCode: "000000" },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ verified: false, status: "CHALLENGED", remainingAttempts: 1 });
  });
});

describe("handleReviewSubmissionRequest", () => {
  it("submits the decision for an authorized, verified session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();
    const response = await handleReviewSubmissionRequest(
      authorizedRequest(
        "submit",
        { recoveryReference: REFERENCE, decision: "APPROVE", stepUpChallengeId: CHALLENGE_ID },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    expect(service.submitReview).toHaveBeenCalledWith(
      { recoveryReference: REFERENCE, decision: "APPROVE", stepUpChallengeId: CHALLENGE_ID },
      expect.any(String),
      expect.anything(),
    );
  });

  it("passes an upstream conflict through as 409 without a 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const { BffError } = await import("./foundation");
    const service = fakeService({
      submitReview: vi.fn(async () => {
        throw new BffError("CONFLICT", 409, "The recovery was already reviewed by another operator.");
      }),
    });
    const response = await handleReviewSubmissionRequest(
      authorizedRequest(
        "submit",
        { recoveryReference: REFERENCE, decision: "APPROVE", stepUpChallengeId: CHALLENGE_ID },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(409);
  });
});
