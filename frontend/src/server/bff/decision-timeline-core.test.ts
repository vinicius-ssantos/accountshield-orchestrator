import { describe, expect, it, vi } from "vitest";

import { BffError } from "./foundation";
import {
  AccountShieldDecisionTimelineClient,
  parseDecisionTimelineInput,
  parseDecisionTimelineResponse,
} from "./decision-timeline-core";

const DECISION_REFERENCE = "00000000-0000-4000-8000-000000000001";
const RESPONSE = {
  decision: {
    decisionReference: DECISION_REFERENCE,
    correlationId: "corr_live_01",
    eventType: "LOGIN_ATTEMPT",
    outcome: "START_RECOVERY",
    riskScore: 82,
    riskBand: "HIGH",
    policyKey: "account-protection",
    policyVersion: "v7",
    decidedAt: "2026-07-29T12:00:00.000Z",
    degraded: false,
    simulated: false,
    provenanceAvailable: true,
  },
  maskedSubjectReference: "acct••••0001",
  reasons: [
    { code: "NEW_DEVICE", contribution: 22, ordinal: 0 },
    { code: "NETWORK_RISK", contribution: 18, ordinal: 1 },
  ],
  signalProvenance: {
    provider: "risk-signal-v2",
    observedAt: "2026-07-29T11:57:00.000Z",
    confidence: "HIGH",
    schemaVersion: "signal.v2",
    state: "RECORDED",
    simulated: false,
    integrityAvailable: true,
  },
  policyProvenance: {
    policyKey: "account-protection",
    policyVersion: "v7",
    routingReason: "ACTIVE_POLICY",
    rolloutCohortBucket: 17,
    rolloutCandidateVersion: null,
    rolloutCandidateSelected: null,
  },
  executionProvenance: {
    algorithmVersion: "risk-score-v3",
    normalizedInputSchemaVersion: "protection-event.v2",
    reasonCatalogVersion: "reasons.v4",
    decisionEngineVersion: "engine.v3",
    applicationCommitSha: "7a91cbe",
    canonicalInputHashAvailable: true,
    auditRecordHashAvailable: true,
  },
  challenges: [
    {
      reference: "challenge-000001",
      challengeType: "TOTP_SIMULATED",
      purpose: "ACCOUNT_PROTECTION",
      status: "ISSUED",
      createdAt: "2026-07-29T12:01:00.000Z",
      expiresAt: "2026-07-29T12:11:00.000Z",
      consumedAt: null,
    },
  ],
  recovery: {
    reference: "recovery-000001",
    directive: "REVERIFY_ACCOUNT_OWNER",
    status: "AUTHORIZED",
    issuedAt: "2026-07-29T12:02:00.000Z",
    expiresAt: "2026-07-29T12:32:00.000Z",
    consumedAt: null,
  },
  outboxEvents: [
    {
      reference: "outbox-000001",
      eventType: "ProtectionDecisionRecorded",
      status: "PUBLISHED",
      occurredAt: "2026-07-29T12:00:00.000Z",
      publishedAt: "2026-07-29T12:01:00.000Z",
      deadLetteredAt: null,
      attemptCount: 1,
    },
  ],
  timeline: [
    {
      reference: DECISION_REFERENCE,
      kind: "DECISION",
      status: "START_RECOVERY",
      occurredAt: "2026-07-29T12:00:00.000Z",
    },
    {
      reference: "outbox-000001",
      kind: "OUTBOX",
      status: "PUBLISHED",
      occurredAt: "2026-07-29T12:00:00.000Z",
    },
    {
      reference: "challenge-000001",
      kind: "CHALLENGE",
      status: "ISSUED",
      occurredAt: "2026-07-29T12:01:00.000Z",
    },
  ],
  sections: {
    challenge: "AVAILABLE",
    recovery: "AVAILABLE",
    outbox: "AVAILABLE",
  },
  partial: false,
};

describe("decision timeline BFF adapter", () => {
  it("uses the generated POST operation and keeps authorization server-side", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(RESPONSE), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = new AccountShieldDecisionTimelineClient({
      origin: "https://accountshield.internal",
      operatorToken: "opaque-test-credential",
      timeoutMs: 1_000,
      maxResponseBytes: 64_000,
      fetchImplementation,
    });

    const result = await client.investigate(
      { decisionReference: DECISION_REFERENCE },
      "bff_correlation_01",
    );

    expect(result).toMatchObject({
      source: "live",
      partial: false,
      maskedSubjectReference: "acct••••0001",
    });
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.toString()).toBe(
      "https://accountshield.internal/api/v1/operator/decisions/investigate",
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
    expect(() => parseDecisionTimelineInput({ decisionReference: "not-a-uuid" })).toThrowError(
      BffError,
    );
    expect(() =>
      parseDecisionTimelineInput({
        decisionReference: DECISION_REFERENCE,
        includeRawPayload: true,
      }),
    ).toThrowError(BffError);
  });

  it("rejects prohibited sensitive fields even when the documented projection is valid", () => {
    expect(() =>
      parseDecisionTimelineResponse({
        ...RESPONSE,
        providerPayload: { deviceFingerprint: "raw-sensitive-value" },
      }),
    ).toThrowError(BffError);
  });

  it("rejects non-deterministic reason and timeline ordering", () => {
    expect(() =>
      parseDecisionTimelineResponse({
        ...RESPONSE,
        reasons: [...RESPONSE.reasons].reverse(),
      }),
    ).toThrowError(BffError);
    expect(() =>
      parseDecisionTimelineResponse({
        ...RESPONSE,
        timeline: [...RESPONSE.timeline].reverse(),
      }),
    ).toThrowError(BffError);
  });

  it("maps backend not-found without exposing its response body", async () => {
    const client = new AccountShieldDecisionTimelineClient({
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
        { decisionReference: DECISION_REFERENCE },
        "bff_correlation_02",
      ),
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      message: "The decision investigation was not found.",
    });
  });
});
