import { describe, expect, it, vi } from "vitest";

import {
  DecisionTimelineBrowserError,
  investigateDecisionThroughBff,
} from "./decision-timeline-browser";

const DECISION_REFERENCE = "00000000-0000-4000-8000-000000000001";
const DETAIL = {
  decision: {
    decisionReference: DECISION_REFERENCE,
    correlationId: "corr_demo_login_8f12",
    eventType: "LOGIN_ATTEMPT",
    riskScore: 82,
    riskBand: "HIGH",
    outcome: "START_RECOVERY",
    policyKey: "account-protection",
    policyVersion: "v7",
    decidedAt: "2026-07-29T04:38:00.000Z",
    degraded: false,
    simulated: false,
    provenanceAvailable: true,
  },
  maskedSubjectReference: "acct••••0001",
  reasons: [{ code: "NEW_DEVICE", contribution: 22, ordinal: 0 }],
  signalProvenance: {
    provider: "risk-signal-v2",
    observedAt: "2026-07-29T04:35:00.000Z",
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
  challenges: [],
  recovery: null,
  outboxEvents: [
    {
      reference: "outbox-000001",
      eventType: "ProtectionDecisionRecorded",
      status: "PUBLISHED",
      occurredAt: "2026-07-29T04:38:00.000Z",
      publishedAt: "2026-07-29T04:39:00.000Z",
      deadLetteredAt: null,
      attemptCount: 1,
    },
  ],
  timeline: [
    {
      reference: DECISION_REFERENCE,
      kind: "DECISION",
      status: "START_RECOVERY",
      occurredAt: "2026-07-29T04:38:00.000Z",
    },
  ],
  sections: {
    challenge: "NOT_APPLICABLE",
    recovery: "NOT_APPLICABLE",
    outbox: "AVAILABLE",
  },
  partial: false,
  source: "fixtures",
};

describe("browser decision-timeline transport", () => {
  it("keeps the opaque decision reference in a same-origin POST body", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(DETAIL), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await investigateDecisionThroughBff(DECISION_REFERENCE, {
      fetchImplementation,
    });

    expect(result.decision.outcome).toBe("START_RECOVERY");
    expect(result.timeline).toHaveLength(1);
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/decision-timeline");
    expect(url).not.toContain(DECISION_REFERENCE);
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({
      decisionReference: DECISION_REFERENCE,
    });
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
  });

  it("keeps arbitrary problem details out of browser errors", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(
        JSON.stringify({
          code: "FORBIDDEN",
          detail: "internal account authorization detail",
          stack: "sensitive stack",
          retryable: false,
        }),
        {
          status: 403,
          headers: { "content-type": "application/problem+json" },
        },
      ),
    );

    const promise = investigateDecisionThroughBff(DECISION_REFERENCE, {
      fetchImplementation,
    });

    await expect(promise).rejects.toBeInstanceOf(DecisionTimelineBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
      retryable: false,
      message: "Decision investigation failed.",
    });
  });

  it("fails closed when a successful response is incomplete", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ decision: DETAIL.decision }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(
      investigateDecisionThroughBff(DECISION_REFERENCE, { fetchImplementation }),
    ).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
