import { describe, expect, it, vi } from "vitest";

import type {
  AccountShieldGeneratedTransport,
  GeneratedTransportRequest,
} from "@/generated/accountshield/openapi-client";
import type {
  ProblemDetails,
  ProtectionDecisionRequest,
  ProtectionDecisionResponse,
} from "@/generated/accountshield/openapi-types";

import {
  ProtectionDecisionContractClient,
  adaptProtectionDecision,
  mapChallengeType,
  mapGeneratedProblem,
  mapProtectionOutcome,
  mapRiskBand,
} from "./protection-decision-contract-core";

const request: ProtectionDecisionRequest = {
  accountReference: "acct_demo_123",
  eventType: "LOGIN_ATTEMPT",
  failedAttempts: 2,
  networkRiskLevel: "MEDIUM",
};

const response: ProtectionDecisionResponse = {
  decisionId: "202d5606-506b-4dc7-a9f8-4d90939b5c75",
  outcome: "REQUIRE_STEP_UP",
  riskScore: 72,
  riskBand: "HIGH",
  algorithmVersion: "risk-v2",
  policyKey: "default-login",
  policyVersion: "7",
  reasons: [
    { code: "NEW_DEVICE", contribution: 20 },
    { code: "FAILED_ATTEMPTS", contribution: 30 },
  ],
  decidedAt: "2026-07-26T20:00:00.000Z",
  challenge: {
    challengeId: "6d150b7f-5a95-4c4a-b89e-dbf40e76ae80",
    challengeType: "TOTP_SIMULATED",
    expiresAt: "2026-07-26T20:05:00.000Z",
  },
};

describe("generated operation adapter", () => {
  it("uses the generated exact method, path, status, body and abort signal", async () => {
    const signal = new AbortController().signal;
    const requestSpy = vi.fn(
      async <TResponse>(transportRequest: GeneratedTransportRequest) => {
        expect(transportRequest).toEqual({
          method: "POST",
          path: "/api/v1/protection-decisions",
          body: request,
          expectedStatus: 201,
          signal,
        });
        return response as TResponse;
      },
    );
    const transport: AccountShieldGeneratedTransport = { request: requestSpy };
    const client = new ProtectionDecisionContractClient(transport);

    await expect(client.create(request, signal)).resolves.toMatchObject({
      decisionId: response.decisionId,
      outcome: "step-up",
      riskBand: "high",
    });
    expect(requestSpy).toHaveBeenCalledTimes(1);
  });

  it("minimizes generated responses before exposing them to feature code", () => {
    const upstream = {
      ...response,
      protectionRequestId: "3f500454-0aa8-49d2-8920-1ce52fbbd048",
      recoveryAuthorizationId: "85c2f849-5437-4c32-8421-b85d781f59f1",
      internalHost: "postgres.internal",
      rawAccountReference: "acct_raw_secret",
    } as ProtectionDecisionResponse & Record<string, unknown>;

    const adapted = adaptProtectionDecision(upstream);
    const serialized = JSON.stringify(adapted);

    expect(adapted).toEqual({
      decisionId: response.decisionId,
      outcome: "step-up",
      riskScore: 72,
      riskBand: "high",
      algorithmVersion: "risk-v2",
      policy: { key: "default-login", version: "7" },
      reasons: [
        { code: "NEW_DEVICE", contribution: 20 },
        { code: "FAILED_ATTEMPTS", contribution: 30 },
      ],
      decidedAt: "2026-07-26T20:00:00.000Z",
      challenge: {
        type: "totp",
        expiresAt: "2026-07-26T20:05:00.000Z",
      },
    });
    expect(serialized).not.toContain("postgres.internal");
    expect(serialized).not.toContain("acct_raw_secret");
    expect(serialized).not.toContain("protectionRequestId");
    expect(serialized).not.toContain("challengeId");
  });
});

describe("additive enum compatibility", () => {
  it("maps unknown additive values to explicit unknown states", () => {
    expect(mapProtectionOutcome("REVIEW_REQUIRED")).toBe("unknown");
    expect(mapRiskBand("VERY_HIGH")).toBe("unknown");
    expect(mapChallengeType("PASSKEY_SIMULATED")).toBe("unknown");
  });
});

describe("generated Problem Details adapter", () => {
  it.each([
    ["INVALID_PROTECTION_REQUEST", "INVALID_REQUEST", 400, false],
    ["ACTIVE_POLICY_UNAVAILABLE", "UPSTREAM_UNAVAILABLE", 503, true],
    ["IDEMPOTENCY_CONFLICT", "CONFLICT", 409, false],
    ["RATE_LIMIT_EXCEEDED", "RATE_LIMITED", 429, true],
  ] as const)(
    "maps %s to a stable frontend code",
    (code, expectedCode, expectedStatus, retryable) => {
      const problem: ProblemDetails = {
        type: `https://accountshield.dev/problems/${code}`,
        title: "Backend title",
        status: expectedStatus,
        code,
        detail: "account acct_raw_secret exists on postgres.internal",
        correlationId: "backend-correlation",
      };

      const error = mapGeneratedProblem(problem);
      const serialized = JSON.stringify(error);

      expect(error).toMatchObject({
        code: expectedCode,
        status: expectedStatus,
        retryable,
      });
      expect(error.message).not.toBe("Backend title");
      expect(serialized).not.toContain("acct_raw_secret");
      expect(serialized).not.toContain("postgres.internal");
      expect(serialized).not.toContain("Backend title");
    },
  );

  it("uses a controlled fallback for unknown future problem codes", () => {
    const error = mapGeneratedProblem({
      type: "https://accountshield.dev/problems/future-problem",
      title: "Future backend title",
      status: 503,
      code: "FUTURE_BACKEND_CODE",
      detail: "sensitive future detail",
    });

    expect(error).toMatchObject({
      code: "UPSTREAM_UNAVAILABLE",
      status: 503,
      retryable: true,
    });
    expect(JSON.stringify(error)).not.toContain("sensitive future detail");
  });
});
