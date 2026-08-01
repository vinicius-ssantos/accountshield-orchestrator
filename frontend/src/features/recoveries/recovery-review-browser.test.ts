import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  RecoveryReviewBrowserError,
  requestReviewStepUp,
  submitRecoveryReview,
  verifyReviewStepUp,
} from "./recovery-review-browser";

const REFERENCE = "00000000-0000-4000-9000-000000000003";
const CHALLENGE_ID = "11111111-1111-4111-9111-111111111111";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "as_csrf=; Max-Age=0; Path=/";
});

describe("requestReviewStepUp", () => {
  it("echoes the CSRF cookie as a header and parses the disclosed code", async () => {
    document.cookie = "as_csrf=csrf-token-value";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ challengeId: CHALLENGE_ID, simulatedCode: "482913" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await requestReviewStepUp(REFERENCE);

    expect(result).toEqual({ challengeId: CHALLENGE_ID, simulatedCode: "482913" });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/bff/recovery-review/step-up");
    expect(init.headers["x-as-csrf-token"]).toBe("csrf-token-value");
    expect(init.credentials).toBe("same-origin");
  });

  it("throws RecoveryReviewBrowserError with the upstream code on failure", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "FORBIDDEN" }), {
        status: 403,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    const rejection = requestReviewStepUp(REFERENCE);
    await expect(rejection).rejects.toBeInstanceOf(RecoveryReviewBrowserError);
    await expect(rejection).rejects.toMatchObject({ code: "FORBIDDEN", status: 403 });
  });
});

describe("verifyReviewStepUp", () => {
  it("sends the reference, challenge id, and provided code", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ verified: true, status: "VERIFIED", remainingAttempts: 2 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await verifyReviewStepUp(REFERENCE, CHALLENGE_ID, "482913");

    expect(result).toEqual({ verified: true, status: "VERIFIED", remainingAttempts: 2 });
    const [, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(JSON.parse(init.body)).toEqual({
      recoveryReference: REFERENCE,
      challengeId: CHALLENGE_ID,
      providedCode: "482913",
    });
  });
});

describe("submitRecoveryReview", () => {
  it("sends the decision and step-up challenge id", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "COMPLETED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await submitRecoveryReview(REFERENCE, "APPROVE", CHALLENGE_ID);

    expect(result).toEqual({ status: "COMPLETED" });
    const [, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(JSON.parse(init.body)).toEqual({
      recoveryReference: REFERENCE,
      decision: "APPROVE",
      stepUpChallengeId: CHALLENGE_ID,
    });
  });
});
