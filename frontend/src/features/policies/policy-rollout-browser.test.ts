import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  PolicyRolloutBrowserError,
  requestPercentageUpdateStepUp,
  requestStartRolloutStepUp,
  rollbackRollout,
  startRollout,
  updateRolloutPercentage,
} from "./policy-rollout-browser";

const KEY = "credential-change-canary";
const VERSION = "2.0.0";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";
const CONTEXT_ID = "22222222-2222-4222-9222-222222222222";

function rolloutBody(overrides: Record<string, unknown> = {}) {
  return {
    policyKey: KEY,
    candidateVersion: VERSION,
    rolloutPercentage: 25,
    status: "ACTIVE",
    startedAt: "2026-07-29T09:00:00.000Z",
    startedBy: "operator-1",
    updatedAt: "2026-07-29T09:00:00.000Z",
    rolledBackAt: null,
    rolledBackBy: null,
    ...overrides,
  };
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "as_csrf=; Max-Age=0; Path=/";
});

describe("requestStartRolloutStepUp", () => {
  it("echoes the CSRF cookie as a header and parses the disclosed code and contextId", async () => {
    document.cookie = "as_csrf=csrf-token-value";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await requestStartRolloutStepUp(KEY, VERSION);

    expect(result).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-rollout/start-step-up");
    expect(init.headers["x-as-csrf-token"]).toBe("csrf-token-value");
    expect(init.credentials).toBe("same-origin");
    expect(JSON.parse(init.body)).toEqual({ policyKey: KEY, candidateVersion: VERSION });
  });

  it("throws PolicyRolloutBrowserError with the upstream code on failure", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "ROLLOUT_CANDIDATE_NOT_APPROVED" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    const rejection = requestStartRolloutStepUp(KEY, VERSION);
    await expect(rejection).rejects.toBeInstanceOf(PolicyRolloutBrowserError);
    await expect(rejection).rejects.toMatchObject({ code: "ROLLOUT_CANDIDATE_NOT_APPROVED", status: 409 });
  });
});

describe("startRollout", () => {
  it("sends the candidate version, percentage, and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify(rolloutBody()), { status: 200, headers: { "content-type": "application/json" } }),
    );

    const result = await startRollout(KEY, VERSION, 25, CHALLENGE_ID);

    expect(result.status).toBe("ACTIVE");
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-rollout/start");
    expect(JSON.parse(init.body)).toEqual({
      policyKey: KEY,
      candidateVersion: VERSION,
      rolloutPercentage: 25,
      stepUpChallengeId: CHALLENGE_ID,
    });
  });
});

describe("requestPercentageUpdateStepUp and updateRolloutPercentage", () => {
  it("requests step-up with only the policy key", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await requestPercentageUpdateStepUp(KEY);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-rollout/percentage-step-up");
    expect(JSON.parse(init.body)).toEqual({ policyKey: KEY });
  });

  it("submits the new percentage and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify(rolloutBody({ rolloutPercentage: 60 })), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await updateRolloutPercentage(KEY, 60, CHALLENGE_ID);
    expect(result.rolloutPercentage).toBe(60);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-rollout/percentage");
    expect(JSON.parse(init.body)).toEqual({ policyKey: KEY, rolloutPercentage: 60, stepUpChallengeId: CHALLENGE_ID });
  });
});

describe("rollbackRollout", () => {
  it("sends only the policy key, with no step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(
        JSON.stringify(rolloutBody({ status: "ROLLED_BACK", rolledBackAt: "2026-07-29T10:00:00.000Z", rolledBackBy: "operator-1" })),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );

    const result = await rollbackRollout(KEY);

    expect(result.status).toBe("ROLLED_BACK");
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-rollout/rollback");
    expect(JSON.parse(init.body)).toEqual({ policyKey: KEY });
  });
});
