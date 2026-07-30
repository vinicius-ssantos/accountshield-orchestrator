import { describe, expect, it, vi } from "vitest";

import {
  DecisionReplayBrowserError,
  replayDecisionThroughBff,
} from "./decision-replay-browser";

const DECISION_REFERENCE = "00000000-0000-4000-8000-000000000001";
const COMPARISON = {
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
  source: "fixtures",
};

describe("browser decision-replay transport", () => {
  it("posts the opaque decision reference in a same-origin body instead of the URL", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify(COMPARISON), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    const result = await replayDecisionThroughBff(DECISION_REFERENCE, { fetchImplementation });

    expect(result.decisionReference).toBe(DECISION_REFERENCE);
    const [url, init] = fetchImplementation.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/bff/decision-replay");
    expect(url).not.toContain(DECISION_REFERENCE);
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ decisionReference: DECISION_REFERENCE });
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

    const promise = replayDecisionThroughBff(DECISION_REFERENCE, { fetchImplementation });

    await expect(promise).rejects.toBeInstanceOf(DecisionReplayBrowserError);
    await expect(promise).rejects.toMatchObject({
      code: "NOT_FOUND",
      status: 404,
      retryable: false,
      message: "Decision replay failed.",
    });
  });

  it("fails closed on malformed successful responses", async () => {
    const fetchImplementation = vi.fn(async () =>
      new Response(JSON.stringify({ original: null }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(
      replayDecisionThroughBff(DECISION_REFERENCE, { fetchImplementation }),
    ).rejects.toMatchObject({
      code: "MALFORMED_RESPONSE",
      status: 502,
    });
  });
});
