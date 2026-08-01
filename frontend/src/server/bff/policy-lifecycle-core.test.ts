import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldPolicyLifecycleClient,
  parseApproveInput,
  parseRejectInput,
  parseStepUpChallengeInput,
  parseStepUpRequestInput,
  parseVerifyStepUpInput,
} from "./policy-lifecycle-core";

const KEY = "account-protection-default";
const VERSION = "2.0.0";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";
const CONTEXT_ID = "22222222-2222-4222-9222-222222222222";

describe("parseStepUpRequestInput", () => {
  it("accepts a valid policyKey and version", () => {
    expect(parseStepUpRequestInput({ policyKey: KEY, version: VERSION })).toEqual({ policyKey: KEY, version: VERSION });
  });

  it("rejects a missing version", () => {
    expect(() => parseStepUpRequestInput({ policyKey: KEY })).toThrow(BffError);
  });

  it("rejects an oversized policyKey", () => {
    expect(() => parseStepUpRequestInput({ policyKey: "x".repeat(101), version: VERSION })).toThrow(BffError);
  });
});

describe("parseVerifyStepUpInput", () => {
  it("accepts a valid payload", () => {
    const input = { challengeId: CHALLENGE_ID, contextId: CONTEXT_ID, providedCode: "731045" };
    expect(parseVerifyStepUpInput(input)).toEqual(input);
  });

  it("rejects a non-UUID contextId", () => {
    expect(() =>
      parseVerifyStepUpInput({ challengeId: CHALLENGE_ID, contextId: "not-a-uuid", providedCode: "731045" }),
    ).toThrow(BffError);
  });

  it("rejects an empty code", () => {
    expect(() =>
      parseVerifyStepUpInput({ challengeId: CHALLENGE_ID, contextId: CONTEXT_ID, providedCode: "" }),
    ).toThrow(BffError);
  });
});

describe("parseApproveInput", () => {
  it("accepts a valid payload with a reason", () => {
    const input = { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "quarterly review" };
    expect(parseApproveInput(input)).toEqual(input);
  });

  it("rejects an empty reason", () => {
    expect(() =>
      parseApproveInput({ policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "" }),
    ).toThrow(BffError);
  });

  it("rejects an oversized reason", () => {
    expect(() =>
      parseApproveInput({
        policyKey: KEY,
        version: VERSION,
        stepUpChallengeId: CHALLENGE_ID,
        reason: "x".repeat(501),
      }),
    ).toThrow(BffError);
  });
});

describe("parseStepUpChallengeInput", () => {
  it("accepts a valid payload", () => {
    const input = { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID };
    expect(parseStepUpChallengeInput(input)).toEqual(input);
  });

  it("rejects a non-UUID stepUpChallengeId", () => {
    expect(() =>
      parseStepUpChallengeInput({ policyKey: KEY, version: VERSION, stepUpChallengeId: "nope" }),
    ).toThrow(BffError);
  });
});

describe("parseRejectInput", () => {
  it("accepts a valid ref", () => {
    expect(parseRejectInput({ policyKey: KEY, version: VERSION })).toEqual({ policyKey: KEY, version: VERSION });
  });
});

describe("AccountShieldPolicyLifecycleClient", () => {
  const client = new AccountShieldPolicyLifecycleClient({
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

  it("requestStepUp posts to the approve/step-up endpoint and parses the disclosed code and contextId", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "731045", contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.requestStepUp("APPROVE", { policyKey: KEY, version: VERSION }, "corr-1");

    expect(result).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "731045", contextId: CONTEXT_ID });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/${VERSION}/approve/step-up`);
    expect(init.headers.authorization).toBe("Bearer operator-token");
  });

  it("requestStepUp posts to the correct path per action", async () => {
    for (const [action, segment] of [
      ["ACTIVATE", "activate"],
      ["RETIRE", "retire"],
    ] as const) {
      (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
        new Response(JSON.stringify({ challengeId: CHALLENGE_ID, contextId: CONTEXT_ID }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
      await client.requestStepUp(action, { policyKey: KEY, version: VERSION }, "corr-1");
      const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
      expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/${VERSION}/${segment}/step-up`);
    }
  });

  it("requestStepUp reports a null simulatedCode when the backend discloses none", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, contextId: CONTEXT_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.requestStepUp("APPROVE", { policyKey: KEY, version: VERSION }, "corr-1");
    expect(result.simulatedCode).toBeNull();
  });

  it("verifyStepUp posts to the challenges verify endpoint with the caller-supplied contextId", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ verified: true, status: "VERIFIED", remainingAttempts: 2 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.verifyStepUp(
      { challengeId: CHALLENGE_ID, contextId: CONTEXT_ID, providedCode: "731045" },
      "corr-1",
    );

    expect(result).toEqual({ verified: true, status: "VERIFIED", remainingAttempts: 2 });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/challenges/${CHALLENGE_ID}/verify`);
    const body = JSON.parse(init.body);
    expect(body).toEqual({ providedCode: "731045", purpose: "PRIVILEGED_OPERATION", contextId: CONTEXT_ID });
  });

  it("approve posts the reason and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "APPROVED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.approve(
      { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "quarterly review" },
      "corr-1",
    );

    expect(result).toEqual({ status: "APPROVED" });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/${VERSION}/approve`);
    expect(JSON.parse(init.body)).toEqual({ stepUpChallengeId: CHALLENGE_ID, reason: "quarterly review" });
  });

  it("reject posts with no body fields", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "REJECTED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.reject({ policyKey: KEY, version: VERSION }, "corr-1");

    expect(result).toEqual({ status: "REJECTED" });
    const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/policies/${KEY}/${VERSION}/reject`);
  });

  it("maps SELF_APPROVAL_NOT_ALLOWED to a distinct, explainable BffError code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "SELF_APPROVAL_NOT_ALLOWED" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(
      client.approve(
        { policyKey: KEY, version: VERSION, stepUpChallengeId: CHALLENGE_ID, reason: "self sign-off" },
        "corr-1",
      ),
    ).rejects.toMatchObject({ code: "SELF_APPROVAL_NOT_ALLOWED", status: 409 });
  });

  it("maps ILLEGAL_TRANSITION to a distinct, explainable BffError code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "ILLEGAL_TRANSITION" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(client.reject({ policyKey: KEY, version: VERSION }, "corr-1")).rejects.toMatchObject({
      code: "ILLEGAL_TRANSITION",
      status: 409,
    });
  });

  it("maps POLICY_VERSION_NOT_FOUND to NOT_FOUND", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "POLICY_VERSION_NOT_FOUND" }), {
        status: 404,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(client.reject({ policyKey: KEY, version: VERSION }, "corr-1")).rejects.toMatchObject({
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
      client.verifyStepUp({ challengeId: CHALLENGE_ID, contextId: CONTEXT_ID, providedCode: "000000" }, "corr-1"),
    ).rejects.toMatchObject({ code: "CONFLICT", status: 410, retryable: true });
  });

  it("maps a 401 upstream failure to UNAUTHORIZED", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({}), { status: 401, headers: { "content-type": "application/problem+json" } }),
    );

    await expect(client.requestStepUp("APPROVE", { policyKey: KEY, version: VERSION }, "corr-1")).rejects.toMatchObject(
      { code: "UNAUTHORIZED", status: 401 },
    );
  });

  it("never leaks the provided code, reason, or backend token in a thrown error message", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response("not json", { status: 200, headers: { "content-type": "application/json" } }),
    );

    try {
      await client.verifyStepUp(
        { challengeId: CHALLENGE_ID, contextId: CONTEXT_ID, providedCode: "731045" },
        "corr-1",
      );
      expect.unreachable();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      expect(message).not.toContain("731045");
      expect(message).not.toContain("operator-token");
    }
  });
});
