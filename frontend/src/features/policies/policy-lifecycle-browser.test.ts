import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  PolicyLifecycleBrowserError,
  activatePolicyVersion,
  approvePolicyVersion,
  rejectPolicyVersion,
  requestLifecycleStepUp,
  retirePolicyVersion,
  verifyLifecycleStepUp,
} from "./policy-lifecycle-browser";

const KEY = "account-protection-default";
const VERSION = "2.0.0";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";
const CONTEXT_ID = "22222222-2222-4222-9222-222222222222";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "as_csrf=; Max-Age=0; Path=/";
});

describe("requestLifecycleStepUp", () => {
  it("echoes the CSRF cookie as a header and parses the disclosed code and contextId", async () => {
    document.cookie = "as_csrf=csrf-token-value";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "731045", contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await requestLifecycleStepUp("APPROVE", KEY, VERSION);

    expect(result).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "731045", contextId: CONTEXT_ID });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-lifecycle/approve-step-up");
    expect(init.headers["x-as-csrf-token"]).toBe("csrf-token-value");
    expect(init.credentials).toBe("same-origin");
  });

  it("posts to the correct endpoint per action", async () => {
    for (const [action, endpoint] of [
      ["ACTIVATE", "/api/bff/policy-lifecycle/activate-step-up"],
      ["RETIRE", "/api/bff/policy-lifecycle/retire-step-up"],
    ] as const) {
      (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
        new Response(JSON.stringify({ challengeId: CHALLENGE_ID, contextId: CONTEXT_ID }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
      await requestLifecycleStepUp(action, KEY, VERSION);
      const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
      expect(url).toBe(endpoint);
    }
  });

  it("throws PolicyLifecycleBrowserError with the upstream code on failure", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "FORBIDDEN" }), {
        status: 403,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    const rejection = requestLifecycleStepUp("APPROVE", KEY, VERSION);
    await expect(rejection).rejects.toBeInstanceOf(PolicyLifecycleBrowserError);
    await expect(rejection).rejects.toMatchObject({ code: "FORBIDDEN", status: 403 });
  });
});

describe("verifyLifecycleStepUp", () => {
  it("sends the challenge id, contextId, and provided code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ verified: true, status: "VERIFIED", remainingAttempts: 2 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await verifyLifecycleStepUp(CHALLENGE_ID, CONTEXT_ID, "731045");

    expect(result).toEqual({ verified: true, status: "VERIFIED", remainingAttempts: 2 });
    const [, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(JSON.parse(init.body)).toEqual({
      challengeId: CHALLENGE_ID,
      contextId: CONTEXT_ID,
      providedCode: "731045",
    });
  });
});

describe("approvePolicyVersion, activatePolicyVersion, rejectPolicyVersion, retirePolicyVersion", () => {
  it("approve sends the reason and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "APPROVED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await approvePolicyVersion(KEY, VERSION, CHALLENGE_ID, "quarterly review");

    expect(result).toEqual({ status: "APPROVED" });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-lifecycle/approve");
    expect(JSON.parse(init.body)).toEqual({
      policyKey: KEY,
      version: VERSION,
      stepUpChallengeId: CHALLENGE_ID,
      reason: "quarterly review",
    });
  });

  it("reject sends only the policy reference", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "REJECTED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await rejectPolicyVersion(KEY, VERSION);

    expect(result).toEqual({ status: "REJECTED" });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/policy-lifecycle/reject");
    expect(JSON.parse(init.body)).toEqual({ policyKey: KEY, version: VERSION });
  });

  it("activate and retire send the step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "ACTIVE" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    await activatePolicyVersion(KEY, VERSION, CHALLENGE_ID);
    const [activateUrl, activateInit] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(activateUrl).toBe("/api/bff/policy-lifecycle/activate");
    expect(JSON.parse(activateInit.body)).toEqual({ policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID });

    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "RETIRED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    await retirePolicyVersion(KEY, VERSION, CHALLENGE_ID);
    const [retireUrl, retireInit] = (fetch as ReturnType<typeof vi.fn>).mock.calls[1];
    expect(retireUrl).toBe("/api/bff/policy-lifecycle/retire");
    expect(JSON.parse(retireInit.body)).toEqual({ policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID });
  });
});
