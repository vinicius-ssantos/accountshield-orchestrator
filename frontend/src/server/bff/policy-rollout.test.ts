import { beforeEach, describe, expect, it, vi } from "vitest";

import { csrfCookieValue } from "./session/csrf";
import { buildSessionCookieValue } from "./session/session-crypto";
import type { SessionRecord } from "./session/session-model";
import { clearSessionStoreForTesting, createSession } from "./session/session-store";
import {
  handlePercentageStepUpRequest,
  handleRollbackRequest,
  handleStartRolloutRequest,
  handleStartStepUpRequest,
  handleUpdatePercentageRequest,
} from "./policy-rollout";
import type { PolicyRolloutService } from "./policy-rollout-core";

const ENV = { NEXT_PUBLIC_APP_ENV: "test" };
const LIVE_ENV = { NEXT_PUBLIC_APP_ENV: "test", ACCOUNTSHIELD_DATA_SOURCE: "live", ACCOUNTSHIELD_API_URL: "http://localhost:8080" };
const SECRET = "accountshield-local-only-session-secret";
const ORIGIN = "https://console.example";
const KEY = "credential-change-canary";
const VERSION = "2.0.0";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";
const CONTEXT_ID = "22222222-2222-4222-9222-222222222222";

function rolloutSummary(overrides: Partial<import("./policy-rollout-core").RolloutSummary> = {}) {
  return {
    policyKey: KEY,
    candidateVersion: VERSION,
    rolloutPercentage: 25,
    status: "ACTIVE" as const,
    startedAt: "2026-07-29T09:00:00.000Z",
    startedBy: "operator-1",
    updatedAt: "2026-07-29T09:00:00.000Z",
    rolledBackAt: null,
    rolledBackBy: null,
    ...overrides,
  };
}

function fakeService(overrides: Partial<PolicyRolloutService> = {}): PolicyRolloutService {
  return {
    requestStartStepUp: vi.fn(async () => ({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID })),
    startRollout: vi.fn(async () => rolloutSummary()),
    requestPercentageStepUp: vi.fn(async () => ({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID })),
    updatePercentage: vi.fn(async () => rolloutSummary({ rolloutPercentage: 60 })),
    rollback: vi.fn(async () => rolloutSummary({ status: "ROLLED_BACK", rolledBackAt: "2026-07-29T10:00:00.000Z", rolledBackBy: "operator-1" })),
    ...overrides,
  };
}

function storedSession(overrides: Partial<SessionRecord> = {}) {
  const now = Date.now();
  const record: SessionRecord = {
    sessionId: "session-id",
    subject: "operator-1",
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
  return new Request(`${ORIGIN}/api/bff/policy-rollout/${path}`, {
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

describe("handleStartStepUpRequest", () => {
  it("requires an authenticated session", async () => {
    // No service injected -- this must exercise the real createPolicyRolloutClient path so the
    // session guard actually runs (a fake service would bypass it entirely).
    const response = await handleStartStepUpRequest(
      new Request(`${ORIGIN}/api/bff/policy-rollout/start-step-up`, {
        method: "POST",
        headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: JSON.stringify({ policyKey: KEY, candidateVersion: VERSION }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(401);
  });

  it("rejects a mutating request missing the CSRF header", async () => {
    const { cookieValue } = storedSession();
    const response = await handleStartStepUpRequest(
      new Request(`${ORIGIN}/api/bff/policy-rollout/start-step-up`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: `as_session=${cookieValue}`,
          "sec-fetch-site": "same-origin",
        },
        body: JSON.stringify({ policyKey: KEY, candidateVersion: VERSION }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(403);
  });

  it("returns the disclosed simulated code and contextId for an authorized session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleStartStepUpRequest(
      authorizedRequest("start-step-up", { policyKey: KEY, candidateVersion: VERSION }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID });
  });

  it("rejects a malformed body with 400, not 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const response = await handleStartStepUpRequest(
      authorizedRequest("start-step-up", { policyKey: "" }, cookieValue, csrfToken),
      fakeService(),
      undefined,
      ENV,
    );
    expect(response.status).toBe(400);
  });
});

describe("handleStartRolloutRequest", () => {
  it("submits the candidate version, percentage, and step-up challenge id for an authorized session", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();
    const response = await handleStartRolloutRequest(
      authorizedRequest(
        "start",
        { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 25, stepUpChallengeId: CHALLENGE_ID },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    expect(service.startRollout).toHaveBeenCalledWith(
      { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 25, stepUpChallengeId: CHALLENGE_ID },
      expect.any(String),
      expect.anything(),
    );
  });

  it("passes an upstream rollout-already-active conflict through as 409 without a 500", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const { BffError } = await import("./foundation");
    const service = fakeService({
      startRollout: vi.fn(async () => {
        throw new BffError("ROLLOUT_ALREADY_ACTIVE", 409, "This policy already has an active rollout.");
      }),
    });
    const response = await handleStartRolloutRequest(
      authorizedRequest(
        "start",
        { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 25, stepUpChallengeId: CHALLENGE_ID },
        cookieValue,
        csrfToken,
      ),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(409);
    const body = await response.json();
    expect(body.code).toBe("ROLLOUT_ALREADY_ACTIVE");
  });
});

describe("handlePercentageStepUpRequest and handleUpdatePercentageRequest", () => {
  it("wire the percentage step-up and update through to the service", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();

    const stepUpResponse = await handlePercentageStepUpRequest(
      authorizedRequest("percentage-step-up", { policyKey: KEY }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(stepUpResponse.status).toBe(200);
    expect(service.requestPercentageStepUp).toHaveBeenCalledWith(
      { policyKey: KEY },
      expect.any(String),
      expect.anything(),
    );

    const updateResponse = await handleUpdatePercentageRequest(
      authorizedRequest("percentage", { policyKey: KEY, rolloutPercentage: 60, stepUpChallengeId: CHALLENGE_ID }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(updateResponse.status).toBe(200);
    expect(service.updatePercentage).toHaveBeenCalledWith(
      { policyKey: KEY, rolloutPercentage: 60, stepUpChallengeId: CHALLENGE_ID },
      expect.any(String),
      expect.anything(),
    );
  });
});

describe("handleRollbackRequest", () => {
  it("rolls back with only the policy key, no step-up challenge id required", async () => {
    const { cookieValue, csrfToken } = storedSession();
    const service = fakeService();
    const response = await handleRollbackRequest(
      authorizedRequest("rollback", { policyKey: KEY }, cookieValue, csrfToken),
      service,
      undefined,
      ENV,
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.status).toBe("ROLLED_BACK");
    expect(service.rollback).toHaveBeenCalledWith({ policyKey: KEY }, expect.any(String), expect.anything());
  });

  it("still requires an authenticated session and CSRF header, same as every other mutation", async () => {
    const response = await handleRollbackRequest(
      new Request(`${ORIGIN}/api/bff/policy-rollout/rollback`, {
        method: "POST",
        headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: JSON.stringify({ policyKey: KEY }),
      }),
      undefined,
      undefined,
      LIVE_ENV,
    );
    expect(response.status).toBe(401);
  });
});
