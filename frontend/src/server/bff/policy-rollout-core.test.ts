import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldPolicyRolloutClient,
  parsePercentageStepUpInput,
  parseRollbackInput,
  parseStartRolloutInput,
  parseStartRolloutStepUpInput,
  parseUpdatePercentageInput,
} from "./policy-rollout-core";

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

describe("parseStartRolloutStepUpInput", () => {
  it("accepts a valid policyKey and candidateVersion", () => {
    expect(parseStartRolloutStepUpInput({ policyKey: KEY, candidateVersion: VERSION })).toEqual({
      policyKey: KEY,
      candidateVersion: VERSION,
    });
  });

  it("rejects a missing candidateVersion", () => {
    expect(() => parseStartRolloutStepUpInput({ policyKey: KEY })).toThrow(BffError);
  });
});

describe("parseStartRolloutInput", () => {
  it("accepts a valid payload", () => {
    const input = { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 10, stepUpChallengeId: CHALLENGE_ID };
    expect(parseStartRolloutInput(input)).toEqual(input);
  });

  it("rejects a non-integer percentage", () => {
    expect(() =>
      parseStartRolloutInput({ policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 10.5, stepUpChallengeId: CHALLENGE_ID }),
    ).toThrow(BffError);
  });

  it("rejects a percentage above 100", () => {
    expect(() =>
      parseStartRolloutInput({ policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 101, stepUpChallengeId: CHALLENGE_ID }),
    ).toThrow(BffError);
  });

  it("rejects a negative percentage", () => {
    expect(() =>
      parseStartRolloutInput({ policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: -1, stepUpChallengeId: CHALLENGE_ID }),
    ).toThrow(BffError);
  });

  it("rejects a non-UUID stepUpChallengeId", () => {
    expect(() =>
      parseStartRolloutInput({ policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 10, stepUpChallengeId: "nope" }),
    ).toThrow(BffError);
  });
});

describe("parsePercentageStepUpInput", () => {
  it("accepts a valid policyKey", () => {
    expect(parsePercentageStepUpInput({ policyKey: KEY })).toEqual({ policyKey: KEY });
  });
});

describe("parseUpdatePercentageInput", () => {
  it("accepts a valid payload", () => {
    const input = { policyKey: KEY, rolloutPercentage: 50, stepUpChallengeId: CHALLENGE_ID };
    expect(parseUpdatePercentageInput(input)).toEqual(input);
  });

  it("rejects an out-of-range percentage", () => {
    expect(() =>
      parseUpdatePercentageInput({ policyKey: KEY, rolloutPercentage: 200, stepUpChallengeId: CHALLENGE_ID }),
    ).toThrow(BffError);
  });
});

describe("parseRollbackInput", () => {
  it("accepts a valid policyKey", () => {
    expect(parseRollbackInput({ policyKey: KEY })).toEqual({ policyKey: KEY });
  });
});

describe("AccountShieldPolicyRolloutClient", () => {
  const client = new AccountShieldPolicyRolloutClient({
    origin: "http://localhost:8080",
    operatorToken: "operator-token",
    timeoutMs: 1000,
  });

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("requestStartStepUp posts to the rollout/step-up endpoint and parses the disclosed code and contextId", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.requestStartStepUp({ policyKey: KEY, candidateVersion: VERSION }, "corr-1");

    expect(result).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "482910", contextId: CONTEXT_ID });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/rollout/step-up`);
    expect(init.method).toBe("POST");
    expect(init.headers.authorization).toBe("Bearer operator-token");
    expect(JSON.parse(init.body)).toEqual({ candidateVersion: VERSION });
  });

  it("startRollout posts the candidate version, percentage, and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify(rolloutBody()), { status: 200, headers: { "content-type": "application/json" } }),
    );

    const result = await client.startRollout(
      { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 25, stepUpChallengeId: CHALLENGE_ID },
      "corr-1",
    );

    expect(result.rolloutPercentage).toBe(25);
    expect(result.status).toBe("ACTIVE");
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/rollout`);
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({
      candidateVersion: VERSION,
      rolloutPercentage: 25,
      stepUpChallengeId: CHALLENGE_ID,
    });
  });

  it("requestPercentageStepUp issues a PATCH with no body", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "111111", contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await client.requestPercentageStepUp({ policyKey: KEY }, "corr-1");

    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/rollout/step-up`);
    expect(init.method).toBe("PATCH");
    expect(init.body).toBeUndefined();
  });

  it("updatePercentage issues a PATCH with the new percentage and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify(rolloutBody({ rolloutPercentage: 60 })), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.updatePercentage(
      { policyKey: KEY, rolloutPercentage: 60, stepUpChallengeId: CHALLENGE_ID },
      "corr-1",
    );

    expect(result.rolloutPercentage).toBe(60);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/rollout`);
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body)).toEqual({ rolloutPercentage: 60, stepUpChallengeId: CHALLENGE_ID });
  });

  it("rollback posts to the rollback endpoint with no step-up and no body", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(
        JSON.stringify(rolloutBody({ status: "ROLLED_BACK", rolledBackAt: "2026-07-29T10:00:00.000Z", rolledBackBy: "operator-1" })),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );

    const result = await client.rollback({ policyKey: KEY }, "corr-1");

    expect(result.status).toBe("ROLLED_BACK");
    expect(result.rolledBackBy).toBe("operator-1");
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/rollout/rollback`);
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
  });

  it("maps ROLLOUT_ALREADY_ACTIVE to a distinct, explainable BffError code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "ROLLOUT_ALREADY_ACTIVE" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(
      client.startRollout(
        { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 10, stepUpChallengeId: CHALLENGE_ID },
        "corr-1",
      ),
    ).rejects.toMatchObject({ code: "ROLLOUT_ALREADY_ACTIVE", status: 409 });
  });

  it("maps ROLLOUT_CANDIDATE_NOT_APPROVED to a distinct, explainable BffError code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "ROLLOUT_CANDIDATE_NOT_APPROVED" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(
      client.startRollout(
        { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 10, stepUpChallengeId: CHALLENGE_ID },
        "corr-1",
      ),
    ).rejects.toMatchObject({ code: "ROLLOUT_CANDIDATE_NOT_APPROVED", status: 409 });
  });

  it("maps POLICY_ROLLOUT_NOT_FOUND to NOT_FOUND", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "POLICY_ROLLOUT_NOT_FOUND" }), {
        status: 404,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(client.rollback({ policyKey: KEY }, "corr-1")).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
    });
  });

  it("maps an expired step-up challenge (410, INVALID_CHALLENGE_STATE) to a retryable CONFLICT", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "INVALID_CHALLENGE_STATE" }), {
        status: 410,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(
      client.startRollout(
        { policyKey: KEY, candidateVersion: VERSION, rolloutPercentage: 10, stepUpChallengeId: CHALLENGE_ID },
        "corr-1",
      ),
    ).rejects.toMatchObject({ code: "CONFLICT", status: 410, retryable: true });
  });

  it("maps a 401 upstream failure to UNAUTHORIZED", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({}), { status: 401, headers: { "content-type": "application/problem+json" } }),
    );

    await expect(client.rollback({ policyKey: KEY }, "corr-1")).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      status: 401,
    });
  });

  it("never leaks the backend token in a thrown error message", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response("not json", { status: 200, headers: { "content-type": "application/json" } }),
    );

    try {
      await client.rollback({ policyKey: KEY }, "corr-1");
      expect.unreachable();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      expect(message).not.toContain("operator-token");
    }
  });
});
