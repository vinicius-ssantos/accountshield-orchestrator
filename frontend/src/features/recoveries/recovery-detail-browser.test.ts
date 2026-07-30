import { describe, expect, it, vi } from "vitest";

import {
  RecoveryDetailBrowserError,
  investigateRecoveryThroughBff,
} from "./recovery-detail-browser";

const RECOVERY_REFERENCE = "00000000-0000-4000-9000-000000000001";
const DETAIL = {
  recovery: {
    recoveryReference: RECOVERY_REFERENCE,
    maskedSubjectReference: "••••7f21",
    eventType: "LOGIN",
    status: "VERIFYING_IDENTITY",
    terminal: false,
    classification: "IMMEDIATE",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 12,
    initiatedAt: "2026-07-30T09:12:00.000Z",
    updatedAt: "2026-07-30T09:13:00.000Z",
    eligibleAfter: null,
    originatingDecisionReference: "••••a001",
    reviewState: "NOT_APPLICABLE",
    challengeExpected: true,
  },
  protectionRequestReference: "••••b001",
  reviewerPresent: false,
  challenges: [
    {
      reference: "challenge-000001",
      challengeType: "TOTP_SIMULATED",
      purpose: "RECOVERY_IDENTITY",
      status: "ISSUED",
      createdAt: "2026-07-30T09:13:00.000Z",
      expiresAt: "2026-07-30T09:23:00.000Z",
      consumedAt: null,
    },
  ],
  challengeAvailability: "AVAILABLE",
  partial: false,
  source: "fixtures",
};

describe("browser recovery-detail transport", () => {
  it("posts the opaque recovery reference in a same-origin body instead of the URL", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(DETAIL), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await investigateRecoveryThroughBff(RECOVERY_REFERENCE, {
      fetchImplementation,
    });

    expect(result.recovery.recoveryReference).toBe(RECOVERY_REFERENCE);
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/recovery-detail");
    expect(url).not.toContain(RECOVERY_REFERENCE);
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ recoveryReference: RECOVERY_REFERENCE });
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
  });

  it("keeps arbitrary problem details out of browser errors", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({
          code: "NOT_FOUND",
          detail: "raw backend persistence detail",
          retryable: false,
        }),
        {
          status: 404,
          headers: { "content-type": "application/problem+json" },
        },
      ),
    );

    const promise = investigateRecoveryThroughBff(RECOVERY_REFERENCE, { fetchImplementation });

    await expect(promise).rejects.toBeInstanceOf(RecoveryDetailBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      retryable: false,
      message: "Recovery investigation failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ recovery: null }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(
      investigateRecoveryThroughBff(RECOVERY_REFERENCE, { fetchImplementation }),
    ).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
