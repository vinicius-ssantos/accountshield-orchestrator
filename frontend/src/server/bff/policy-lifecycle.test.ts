import { beforeEach, describe, expect, it, vi } from "vitest";

import { csrfCookieValue } from "./session/csrf";
import { buildSessionCookieValue } from "./session/session-crypto";
import type { SessionRecord } from "./session/session-model";
import { clearSessionStoreForTesting, createSession } from "./session/session-store";
import {
  handleActivateRequest,
  handleActivationStepUpRequest,
  handleApprovalStepUpRequest,
  handleApproveRequest,
  handleRejectRequest,
  handleRetireRequest,
  handleRetirementStepUpRequest,
  handleVerifyStepUpRequest,
} from "./policy-lifecycle";
import type { PolicyLifecycleService } from "./policy-lifecycle-core";

const ENV = { NEXT_PUBLIC_APP_ENV: "test" };
const LIVE_ENV = { NEXT_PUBLIC_APP_ENV: "test", ACCOUNTSHIELD_DATA_SOURCE: "live", ACCOUNTSHIELD_API_URL: "http://localhost:8080" };
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";
const KEY = "account-protection-default";
const VERSION = "2.0.0";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";
const CONTEXT_ID = "22222222-2222-4222-9222-222222222222";

function fakeService(overrides: Partial<PolicyLifecycleService> = {}): PolicyLifecycleService {
  return {
    requestStepUp: vi.fn(async () => ({ challengeId: CHALLENGE_ID, simulatedCode: "731045", contextId: CONTEXT_ID })),
    verifyStepUp: vi.fn(async () => ({ verified: true, status: "VERIFIED", remainingAttempts: 3 })),
    approve: vi.fn(async () => ({ status: "APPROVED" })),
    activate: vi.fn(async () => ({ status: "ACTIVE" })),
    reject: vi.fn(async () => ({ status: "REJECTED" })),
    retire: vi.fn(async () => ({ status: "RETIRED" })),
    ...overrides,
  };
}

function storedSession(overrides: Partial<SessionRecord> = {}) {
  const now = Date.now();
  const record: SessionRecord = {
    sessionId: "session-id",
    subject: "approver-1",
    roles: ["POLICY_ADMIN"],
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
  return new Request(`${ORIGIN}/api/bff/policy-lifecycle/${path}`, {
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

describe("handleApprovalStepUpRequest", () => {
  it("requires an authenticated session", async () => {
    // No service injected -- this must exercise the real createPolicyLifecycleClient path so
    // the session guard actually runs (a fake service would bypass it entirely).
    const response = await handleApprovalStepUpRequest(
      new Request(`${ORIGIN}/api/bff/policy-lifecycle/approve-step-up`, {
        method: "POST",
        headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: JSON.stringify({ policyKey: KEY, version: VERSION }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(401);
  });

  it("rejects a mutating request missing the CSRF header", async () => {
    const { cookieValue } = storedSession();
    const response = await handleApprovalStepUpRequest(
      new Request(`${ORIGIN}/api/bff/policy-lifecycle/approve-step-up`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
        },
        body: JSON.stringify({ policyKey: KEY, version: VERSION }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(403);
  });

  it("returns the disclosed simulated code and contextId for an authorized session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleApprovalStepUpRequest(
      authorizedRequest("approve-step-up", { policyKey: KEY, version: VERSION }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "731045", contextId: CONTEXT_ID });
  });

  it("rejects a malformed body with 400, not 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleApprovalStepUpRequest(
      authorizedRequest("approve-step-up", { policyKey: "" }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(400);
  });
});

describe("handleActivationStepUpRequest and handleRetirementStepUpRequest", () => {
  it("call requestStepUp with the correct action for each handler", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();

    await handleActivationStepUpRequest(
      authorizedRequest("activate-step-up", { policyKey: KEY, version: VERSION }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(service.requestStepUp).toHaveBeenCalledWith(
      "ACTIVATE",
      { policyKey: KEY, version: VERSION },
      expect.any(String),
      expect.anything(),
    );

    await handleRetirementStepUpRequest(
      authorizedRequest("retire-step-up", { policyKey: KEY, version: VERSION }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(service.requestStepUp).toHaveBeenCalledWith(
      "RETIRE",
      { policyKey: KEY, version: VERSION },
      expect.any(String),
      expect.anything(),
    );
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
        { challengeId: CHALLENGE_ID, contextId: CONTEXT_ID, providedCode: "000000" },
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

describe("handleApproveRequest", () => {
  it("submits the reason and step-up challenge id for an authorized, verified session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();
    const response = await handleApproveRequest(
      authorizedRequest(
        "approve",
        { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "quarterly review" },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    expect(service.approve).toHaveBeenCalledWith(
      { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "quarterly review" },
      expect.any(String),
      expect.anything(),
    );
  });

  it("passes an upstream self-approval conflict through as 409 without a 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const { BffError } = await import("./foundation");
    const service = fakeService({
      approve: vi.fn(async () => {
        throw new BffError("SELF_APPROVAL_NOT_ALLOWED", 409, "The authenticated operator authored this version.");
      }),
    });
    const response = await handleApproveRequest(
      authorizedRequest(
        "approve",
        { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "self sign-off" },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(409);
    const body = await response.json();
    expect(body.code).toBe("SELF_APPROVAL_NOT_ALLOWED");
  });
});

describe("handleActivateRequest, handleRejectRequest, handleRetireRequest", () => {
  it("wire each action through to its service method with only the fields it needs", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();

    const activateResponse = await handleActivateRequest(
      authorizedRequest("activate", { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(activateResponse.status).toBe(200);
    expect(service.activate).toHaveBeenCalledWith(
      { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID },
      expect.any(String),
      expect.anything(),
    );

    const rejectResponse = await handleRejectRequest(
      authorizedRequest("reject", { policyKey: KEY, version: VERSION }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(rejectResponse.status).toBe(200);
    expect(service.reject).toHaveBeenCalledWith({ policyKey: KEY, version: VERSION }, expect.any(String), expect.anything());

    const retireResponse = await handleRetireRequest(
      authorizedRequest("retire", { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(retireResponse.status).toBe(200);
    expect(service.retire).toHaveBeenCalledWith(
      { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID },
      expect.any(String),
      expect.anything(),
    );
  });
});
