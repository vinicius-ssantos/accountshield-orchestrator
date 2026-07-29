import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { DecisionTimelinePanel } from "./decision-timeline-panel";

const mocks = vi.hoisted(() => ({
  investigate: vi.fn(),
}));

vi.mock("./decision-timeline-browser", () => ({
  DecisionTimelineBrowserError: class DecisionTimelineBrowserError extends Error {
    constructor(
      readonly code: string,
      readonly status: number,
      readonly retryable: boolean,
    ) {
      super("Decision investigation failed.");
    }
  },
  investigateDecisionThroughBff: mocks.investigate,
}));

const DECISION_REFERENCE = "00000000-0000-4000-8000-000000000001";

const DETAIL = {
  decision: {
    decisionReference: DECISION_REFERENCE,
    correlationId: "corr_component_8f12",
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
  reasons: [
    { code: "NEW_DEVICE", contribution: 22, ordinal: 0 },
    { code: "NETWORK_RISK", contribution: 18, ordinal: 1 },
  ],
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
    {
      reference: "outbox-000001",
      kind: "OUTBOX",
      status: "PUBLISHED",
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
} as const;

describe("DecisionTimelinePanel", () => {
  it("renders an accessible ordered explanation without the raw decision reference", async () => {
    mocks.investigate.mockResolvedValueOnce(DETAIL);
    const onClose = vi.fn();
    const { container } = render(
      <DecisionTimelinePanel
        decisionReference={DECISION_REFERENCE}
        onClose={onClose}
      />,
    );

    expect(
      await screen.findByRole("heading", { level: 2, name: "Decision explanation" }),
    ).toBeVisible();
    expect(screen.getByRole("region", { name: "Signal provenance" })).toBeVisible();
    expect(screen.getByRole("region", { name: "Policy provenance" })).toBeVisible();
    expect(screen.getByRole("region", { name: "Execution provenance" })).toBeVisible();
    expect(screen.getByRole("list", { name: "Decision event timeline" })).toBeVisible();
    expect(screen.getAllByLabelText("Masked event reference")).toHaveLength(2);
    expect(container.textContent).not.toContain(DECISION_REFERENCE);
    expect(container.innerHTML).not.toContain(DECISION_REFERENCE);

    fireEvent.click(screen.getByRole("button", { name: "Close investigation" }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
