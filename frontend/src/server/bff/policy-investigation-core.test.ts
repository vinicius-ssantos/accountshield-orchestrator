import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldPolicyInvestigationClient,
  parsePolicyInvestigationInput,
  parsePolicyInvestigationResponse,
} from "./policy-investigation-core";

const RESPONSE = {
  policyKey: "account-protection-default",
  versions: [
    {
      id: "00000000-0000-4000-a000-000000000002",
      policyKey: "account-protection-default",
      version: "1.0.0",
      status: "ACTIVE",
      allowMaxScore: 25,
      stepUpMaxScore: 65,
      recoveryMaxScore: 89,
      createdAt: "2026-06-01T09:00:00.000Z",
      activatedAt: "2026-06-02T09:00:00.000Z",
      analysis: { analyzerVersion: "policy-analyzer-1.0", diagnostics: [] },
      governance: {
        createdBy: "policy-author",
        validatedBy: "policy-validator",
        validatedAt: "2026-06-01T10:00:00.000Z",
        approvedBy: "policy-approver",
        approvedAt: "2026-06-02T08:00:00.000Z",
        approvalReason: "Initial default policy.",
      },
    },
  ],
  routingScope: [{ clientId: "default-client", eventType: "LOGIN_ATTEMPT" }],
  activeRollout: null,
  impactAnalysis: null,
  impactAvailability: "NOT_APPLICABLE",
};

const AVAILABLE_RESPONSE = {
  ...RESPONSE,
  activeRollout: {
    candidateVersion: "2.0.0",
    rolloutPercentage: 25,
    status: "ACTIVE",
    startedAt: "2026-07-29T09:00:00.000Z",
    startedBy: "operator-1",
    updatedAt: "2026-07-29T09:00:00.000Z",
    rolledBackAt: null,
    rolledBackBy: null,
  },
  impactAvailability: "AVAILABLE",
  impactAnalysis: {
    candidatePolicyVersion: "2.0.0",
    originalPolicyVersionsObserved: ["1.0.0"],
    algorithmVersionsObserved: ["risk-score-v3"],
    totalDecisions: 10,
    divergentDecisionsCount: 1,
    divergencePercentage: 10,
    maxDivergencePercentageThreshold: 20,
    exceedsDivergenceThreshold: false,
    transitionMatrix: { ALLOW: { ALLOW: 9, REQUIRE_STEP_UP: 1 } },
    impactByEventType: { LOGIN_ATTEMPT: { segment: "LOGIN_ATTEMPT", totalDecisions: 10, divergentDecisions: 1 } },
    impactByRiskBand: { LOW: { segment: "LOW", totalDecisions: 10, divergentDecisions: 1 } },
    divergentDecisions: [
      {
        maskedProtectionRequestReference: "••••7a01",
        redactedAccountReference: "9f2e1c7b4a6d8035",
        originalOutcome: "ALLOW",
        candidateOutcome: "REQUIRE_STEP_UP",
        riskScore: 42,
        originalReasons: [{ code: "NEW_DEVICE", contribution: 22 }],
      },
    ],
  },
};

describe("policy investigation BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldPolicyInvestigationClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation,
    });

    const result = await client.investigate(
      { policyKey: "account-protection-default" },
      "bff_correlation_01",
    );

    expect(result).toMatchObject({ source: "live", policyKey: "account-protection-default" });
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe(
      "https://accountshield.internal/api/v1/operator/policies/investigate",
    );
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ policyKey: "account-protection-default" });
  });

  it("accepts an AVAILABLE impact analysis with masked divergent decisions", () => {
    expect(() => parsePolicyInvestigationResponse(AVAILABLE_RESPONSE)).not.toThrow();
  });

  it("rejects malformed or additional request fields before backend access", () => {
    expect(() => parsePolicyInvestigationInput({ policyKey: "" })).toThrowError(BffError);
    expect(() =>
      parsePolicyInvestigationInput({ policyKey: "account-protection-default", extra: true }),
    ).toThrowError(BffError);
  });

  it("rejects a raw unmasked accountReference or protectionRequestId while allowing their masked variants", () => {
    expect(() => parsePolicyInvestigationResponse(RESPONSE)).not.toThrow();
    expect(() =>
      parsePolicyInvestigationResponse({
        ...RESPONSE,
        versions: [{ ...RESPONSE.versions[0], accountReference: "raw-leak" }],
      }),
    ).toThrowError(BffError);
    expect(() =>
      parsePolicyInvestigationResponse({
        ...RESPONSE,
        versions: [{ ...RESPONSE.versions[0], protectionRequestId: "raw-leak" }],
      }),
    ).toThrowError(BffError);
  });

  it("rejects an AVAILABLE availability with no impact analysis payload", () => {
    expect(() =>
      parsePolicyInvestigationResponse({ ...RESPONSE, impactAvailability: "AVAILABLE", impactAnalysis: null }),
    ).toThrowError(BffError);
  });

  it("maps backend not-found without exposing its response body", async () => {
    const client = new AccountShieldPolicyInvestigationClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ detail: "internal persistence detail" }), {
          status: 404,
          headers: { "content-type": "application/problem+json" },
        }),
      ),
    });

    await expect(
      client.investigate({ policyKey: "unknown-policy" }, "bff_correlation_02"),
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      message: "The policy investigation was not found.",
    });
  });
});
