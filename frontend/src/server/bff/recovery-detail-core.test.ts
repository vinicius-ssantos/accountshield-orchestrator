import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldRecoveryDetailClient,
  parseRecoveryDetailInput,
  parseRecoveryDetailResponse,
} from "./recovery-detail-core";

const RECOVERY_REFERENCE = "00000000-0000-4000-9000-000000000001";
const RESPONSE = {
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
};

describe("recovery detail BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldRecoveryDetailClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation,
    });

    const result = await client.investigate(
      { recoveryReference: RECOVERY_REFERENCE },
      "bff_correlation_01",
    );

    expect(result).toMatchObject({
      source: "live",
      partial: false,
      reviewerPresent: false,
      challengeAvailability: "AVAILABLE",
    });
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe(
      "https://accountshield.internal/api/v1/operator/recoveries/investigate",
    );
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      authorization: "Bearer opaque-test-credential",
      "content-type": "application/json",
      "x-correlation-id": "bff_correlation_01",
    });
    expect(JSON.parse(String(init.body))).toEqual({
      recoveryReference: RECOVERY_REFERENCE,
    });
  });

  it("rejects malformed or additional request fields before backend access", () => {
    expect(() => parseRecoveryDetailInput({ recoveryReference: "not-a-uuid" })).toThrowError(
      BffError,
    );
    expect(() =>
      parseRecoveryDetailInput({
        recoveryReference: RECOVERY_REFERENCE,
        includeRawPayload: true,
      }),
    ).toThrowError(BffError);
  });

  it("rejects prohibited sensitive fields even when the documented projection is valid", () => {
    expect(() =>
      parseRecoveryDetailResponse({
        ...RESPONSE,
        providerPayload: { deviceFingerprint: "raw-sensitive-value" },
      }),
    ).toThrowError(BffError);
  });

  it("accepts the reviewerPresent boolean field without treating it as prohibited", () => {
    expect(() => parseRecoveryDetailResponse(RESPONSE)).not.toThrow();
  });

  it("maps backend not-found without exposing its response body", async () => {
    const client = new AccountShieldRecoveryDetailClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ detail: "internal account reference" }), {
          status: 404,
          headers: { "content-type": "application/problem+json" },
        }),
      ),
    });

    await expect(
      client.investigate(
        { recoveryReference: RECOVERY_REFERENCE },
        "bff_correlation_02",
      ),
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      message: "The recovery investigation was not found.",
    });
  });
});
