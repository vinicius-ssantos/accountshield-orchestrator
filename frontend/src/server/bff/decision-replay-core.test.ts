import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldDecisionReplayClient,
  parseDecisionReplayInput,
  parseDecisionReplayResponse,
} from "./decision-replay-core";

const DECISION_REFERENCE = "00000000-0000-4000-8000-000000000001";
const RESPONSE = {
  decisionReference: DECISION_REFERENCE,
  maskedSubjectReference: "acct••••0001",
  matches: true,
  original: {
    outcome: "ALLOW",
    riskScore: 12,
    riskBand: "LOW",
    reasons: [{ code: "KNOWN_DEVICE", contribution: 12 }],
  },
  replayed: {
    outcome: "ALLOW",
    riskScore: 12,
    riskBand: "LOW",
    reasons: [{ code: "KNOWN_DEVICE", contribution: 12 }],
  },
  policyKey: "account-protection",
  policyVersion: "v7",
  algorithmVersion: "risk-score-v3",
  normalizedInputSchemaVersion: "protection-event.v2",
  reasonCatalogVersion: "reasons.v4",
  decisionEngineVersion: "engine.v3",
  mismatches: [],
};

describe("decision replay BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldDecisionReplayClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation,
    });

    const result = await client.replay(
      { decisionReference: DECISION_REFERENCE },
      "bff_correlation_01",
    );

    expect(result).toMatchObject({
      source: "live",
      matches: true,
      maskedSubjectReference: "acct••••0001",
    });
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe(
      "https://accountshield.internal/api/v1/operator/decisions/replay",
    );
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      authorization: "Bearer opaque-test-credential",
      "content-type": "application/json",
      "x-correlation-id": "bff_correlation_01",
    });
    expect(JSON.parse(String(init.body))).toEqual({
      decisionReference: DECISION_REFERENCE,
    });
  });

  it("rejects malformed or additional request fields before backend access", () => {
    expect(() => parseDecisionReplayInput({ decisionReference: "not-a-uuid" })).toThrowError(
      BffError,
    );
    expect(() =>
      parseDecisionReplayInput({
        decisionReference: DECISION_REFERENCE,
        includeRawPayload: true,
      }),
    ).toThrowError(BffError);
  });

  it("rejects prohibited sensitive fields even when the documented projection is valid", () => {
    expect(() =>
      parseDecisionReplayResponse({
        ...RESPONSE,
        providerPayload: { deviceFingerprint: "raw-sensitive-value" },
      }),
    ).toThrowError(BffError);
    expect(() =>
      parseDecisionReplayResponse({
        ...RESPONSE,
        protectionRequestId: "550e8400-e29b-41d4-a716-446655440000",
      }),
    ).toThrowError(BffError);
  });

  it("accepts a divergent comparison with populated mismatches", () => {
    const divergent = {
      ...RESPONSE,
      matches: false,
      replayed: { ...RESPONSE.replayed, riskScore: 18, outcome: "REQUIRE_STEP_UP" },
      mismatches: ["riskScore: expected 12 but replay produced 18"],
    };
    expect(parseDecisionReplayResponse(divergent)).toMatchObject({
      matches: false,
      mismatches: ["riskScore: expected 12 but replay produced 18"],
    });
  });

  it("maps backend not-found without exposing its response body", async () => {
    const client = new AccountShieldDecisionReplayClient({
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
      client.replay({ decisionReference: DECISION_REFERENCE }, "bff_correlation_02"),
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      message: "The decision replay was not found.",
    });
  });

  it("maps backend unavailable-version responses to a retryable-false unavailable error", async () => {
    const client = new AccountShieldDecisionReplayClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation: vi.fn(async () =>
        new Response(JSON.stringify({ detail: "unknown algorithm version" }), {
          status: 503,
          headers: { "content-type": "application/problem+json" },
        }),
      ),
    });

    await expect(
      client.replay({ decisionReference: DECISION_REFERENCE }, "bff_correlation_03"),
    ).rejects.toMatchObject({
      code: "UPSTREAM_UNAVAILABLE",
      status: 503,
    });
  });
});
