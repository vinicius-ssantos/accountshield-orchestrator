import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldRecoveryReviewClient,
  parseReviewSubmissionInput,
  parseStepUpRequestInput,
  parseVerifyStepUpInput,
} from "./recovery-review-core";

const REFERENCE = "00000000-0000-4000-9000-000000000003";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";

describe("parseStepUpRequestInput", () => {
  it("accepts a valid reference", () => {
    expect(parseStepUpRequestInput({ recoveryReference: REFERENCE })).toEqual({ recoveryReference: REFERENCE });
  });

  it("rejects a non-UUID reference", () => {
    expect(() => parseStepUpRequestInput({ recoveryReference: "not-a-uuid" })).toThrow(BffError);
  });

  it("rejects a missing reference", () => {
    expect(() => parseStepUpRequestInput({})).toThrow(BffError);
  });
});

describe("parseVerifyStepUpInput", () => {
  it("accepts a valid payload", () => {
    const input = { recoveryReference: REFERENCE, challengeId: CHALLENGE_ID, providedCode: "482913" };
    expect(parseVerifyStepUpInput(input)).toEqual(input);
  });

  it("rejects an empty code", () => {
    expect(() =>
      parseVerifyStepUpInput({ recoveryReference: REFERENCE, challengeId: CHALLENGE_ID, providedCode: "" }),
    ).toThrow(BffError);
  });

  it("rejects an oversized code", () => {
    expect(() =>
      parseVerifyStepUpInput({
        recoveryReference: REFERENCE,
        challengeId: CHALLENGE_ID,
        providedCode: "x".repeat(65),
      }),
    ).toThrow(BffError);
  });
});

describe("parseReviewSubmissionInput", () => {
  it("accepts APPROVE and REJECT", () => {
    for (const decision of ["APPROVE", "REJECT"]) {
      expect(
        parseReviewSubmissionInput({ recoveryReference: REFERENCE, decision, stepUpChallengeId: CHALLENGE_ID }),
      ).toEqual({ recoveryReference: REFERENCE, decision, stepUpChallengeId: CHALLENGE_ID });
    }
  });

  it("rejects an unknown decision", () => {
    expect(() =>
      parseReviewSubmissionInput({ recoveryReference: REFERENCE, decision: "MAYBE", stepUpChallengeId: CHALLENGE_ID }),
    ).toThrow(BffError);
  });
});

describe("AccountShieldRecoveryReviewClient", () => {
  const client = new AccountShieldRecoveryReviewClient({
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

  it("requestStepUp posts to the review/step-up endpoint and parses the disclosed code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "482913" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.requestStepUp({ recoveryReference: REFERENCE }, "corr-1");

    expect(result).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "482913" });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/recovery/${REFERENCE}/review/step-up`);
    expect(init.headers.authorization).toBe("Bearer operator-token");
  });

  it("requestStepUp reports a null simulatedCode when the backend discloses none", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.requestStepUp({ recoveryReference: REFERENCE }, "corr-1");
    expect(result.simulatedCode).toBeNull();
  });

  it("verifyStepUp posts to the challenges verify endpoint with the review purpose", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ verified: true, status: "VERIFIED", remainingAttempts: 2 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.verifyStepUp(
      { recoveryReference: REFERENCE, challengeId: CHALLENGE_ID, providedCode: "482913" },
      "corr-1",
    );

    expect(result).toEqual({ verified: true, status: "VERIFIED", remainingAttempts: 2 });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/challenges/${CHALLENGE_ID}/verify`);
    const body = JSON.parse(init.body);
    expect(body).toEqual({ providedCode: "482913", purpose: "PRIVILEGED_OPERATION", contextId: REFERENCE });
  });

  it("submitReview posts the decision and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "COMPLETED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await client.submitReview(
      { recoveryReference: REFERENCE, decision: "APPROVE", stepUpChallengeId: CHALLENGE_ID },
      "corr-1",
    );

    expect(result).toEqual({ status: "COMPLETED" });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`http://localhost:8080/api/v1/recovery/${REFERENCE}/review`);
    expect(JSON.parse(init.body)).toEqual({ decision: "APPROVE", stepUpChallengeId: CHALLENGE_ID });
  });

  it("maps a 409 upstream conflict to a BffError with a stable code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "RECOVERY_CONFLICT" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(client.submitReview(
      { recoveryReference: REFERENCE, decision: "APPROVE", stepUpChallengeId: CHALLENGE_ID },
      "corr-1",
    )).rejects.toMatchObject({ code: "CONFLICT", status: 409 });
  });

  it("maps a 401 upstream failure to UNAUTHORIZED", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({}), { status: 401, headers: { "content-type": "application/problem+json" } }),
    );

    await expect(client.requestStepUp({ recoveryReference: REFERENCE }, "corr-1")).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      status: 401,
    });
  });

  it("never logs or echoes the provided code or backend token in a thrown error message", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response("not json", { status: 200, headers: { "content-type": "application/json" } }),
    );

    try {
      await client.verifyStepUp(
        { recoveryReference: REFERENCE, challengeId: CHALLENGE_ID, providedCode: "482913" },
        "corr-1",
      );
      expect.unreachable();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      expect(message).not.toContain("482913");
      expect(message).not.toContain("operator-token");
    }
  });
});
