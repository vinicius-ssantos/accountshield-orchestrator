import type {
  DecisionChallengeSummary,
  DecisionInvestigationDetail,
  DecisionOutboxSummary,
  DecisionRecoverySummary,
  DecisionSummary,
  DecisionTimelineEntry,
} from "./types";

function plusMinutes(instant: string, minutes: number): string {
  return new Date(new Date(instant).valueOf() + minutes * 60_000).toISOString();
}

function challenge(
  decision: DecisionSummary,
  status = "ISSUED",
): DecisionChallengeSummary {
  return {
    reference: `challenge-${decision.decisionReference.slice(-6)}`,
    challengeType: "TOTP_SIMULATED",
    purpose: "ACCOUNT_PROTECTION",
    status,
    createdAt: plusMinutes(decision.decidedAt, 1),
    expiresAt: plusMinutes(decision.decidedAt, 11),
    consumedAt: status === "CONSUMED" ? plusMinutes(decision.decidedAt, 4) : null,
  };
}

function recovery(
  decision: DecisionSummary,
  status = "AUTHORIZED",
): DecisionRecoverySummary {
  return {
    reference: `recovery-${decision.decisionReference.slice(-6)}`,
    directive: "REVERIFY_ACCOUNT_OWNER",
    status,
    issuedAt: plusMinutes(decision.decidedAt, 2),
    expiresAt: plusMinutes(decision.decidedAt, 32),
    consumedAt: status === "CONSUMED" ? plusMinutes(decision.decidedAt, 7) : null,
  };
}

function outbox(
  decision: DecisionSummary,
  status = "PUBLISHED",
): DecisionOutboxSummary {
  return {
    reference: `outbox-${decision.decisionReference.slice(-6)}`,
    eventType: "ProtectionDecisionRecorded",
    status,
    occurredAt: plusMinutes(decision.decidedAt, 0),
    publishedAt: status === "PUBLISHED" ? plusMinutes(decision.decidedAt, 1) : null,
    deadLetteredAt: status === "DEAD_LETTERED" ? plusMinutes(decision.decidedAt, 6) : null,
    attemptCount: status === "PUBLISHED" ? 1 : 3,
  };
}

function timeline(
  decision: DecisionSummary,
  challenges: readonly DecisionChallengeSummary[],
  recoverySummary: DecisionRecoverySummary | null,
  outboxEvents: readonly DecisionOutboxSummary[],
): readonly DecisionTimelineEntry[] {
  const entries: DecisionTimelineEntry[] = [
    {
      reference: decision.decisionReference,
      kind: "DECISION",
      status: decision.outcome,
      occurredAt: decision.decidedAt,
    },
    ...outboxEvents.map((event) => ({
      reference: event.reference,
      kind: "OUTBOX",
      status: event.status,
      occurredAt: event.occurredAt,
    })),
    ...challenges.map((item) => ({
      reference: item.reference,
      kind: "CHALLENGE",
      status: item.status,
      occurredAt: item.createdAt,
    })),
  ];
  if (recoverySummary) {
    entries.push({
      reference: recoverySummary.reference,
      kind: "RECOVERY",
      status: recoverySummary.status,
      occurredAt: recoverySummary.issuedAt,
    });
  }
  return entries.sort((left, right) =>
    left.occurredAt.localeCompare(right.occurredAt) ||
    left.kind.localeCompare(right.kind) ||
    left.reference.localeCompare(right.reference),
  );
}

export function buildFixtureDecisionTimeline(
  decision: DecisionSummary,
): DecisionInvestigationDetail {
  const scenario = decision.decisionReference.slice(-1);
  const completeChallenge = challenge(decision, scenario === "6" ? "CONSUMED" : "ISSUED");
  const completeRecovery = recovery(decision, scenario === "5" ? "CONSUMED" : "AUTHORIZED");
  const publishedOutbox = outbox(decision);

  const challenges =
    scenario === "1" || scenario === "5" || scenario === "6"
      ? [completeChallenge]
      : [];
  const recoverySummary = scenario === "1" || scenario === "5" ? completeRecovery : null;
  const outboxEvents = scenario === "3" ? [] : [publishedOutbox];

  const signalState =
    scenario === "2"
      ? "STALE"
      : scenario === "3"
        ? "UNAVAILABLE"
        : scenario === "5"
          ? "SIMULATED"
          : "RECORDED";

  const partial = scenario === "2" || scenario === "3";
  const unavailable = scenario === "3";

  return {
    decision,
    maskedSubjectReference: `acct••••${scenario.padStart(4, "0")}`,
    reasons:
      scenario === "4"
        ? [{ code: "KNOWN_DEVICE", contribution: -12, ordinal: 0 }]
        : [
            { code: "NEW_DEVICE", contribution: 22, ordinal: 0 },
            { code: "NETWORK_RISK", contribution: 18, ordinal: 1 },
            ...(scenario === "1" || scenario === "5"
              ? [{ code: "RECOVERY_SIGNAL", contribution: 31, ordinal: 2 }]
              : []),
          ],
    signalProvenance: {
      provider: unavailable ? null : scenario === "5" ? "fixture-simulator" : "risk-signal-v2",
      observedAt: unavailable ? null : plusMinutes(decision.decidedAt, -3),
      confidence: unavailable ? null : scenario === "2" || scenario === "6" ? "LOW" : "HIGH",
      schemaVersion: unavailable ? null : "signal.v2",
      state: signalState,
      simulated: scenario === "5",
      integrityAvailable: !unavailable && scenario !== "2",
    },
    policyProvenance: {
      policyKey: decision.policyKey,
      policyVersion: decision.policyVersion,
      routingReason: unavailable ? "UNAVAILABLE" : "ACTIVE_POLICY",
      rolloutCohortBucket: unavailable ? null : 17,
      rolloutCandidateVersion: scenario === "6" ? "v3" : null,
      rolloutCandidateSelected: scenario === "6" ? false : null,
    },
    executionProvenance: {
      algorithmVersion: unavailable ? "unavailable" : "risk-score-v3",
      normalizedInputSchemaVersion: unavailable ? null : "protection-event.v2",
      reasonCatalogVersion: unavailable ? null : "reasons.v4",
      decisionEngineVersion: unavailable ? null : "engine.v3",
      applicationCommitSha: unavailable ? null : "7a91cbe",
      canonicalInputHashAvailable: !unavailable,
      auditRecordHashAvailable: !unavailable && scenario !== "2",
    },
    challenges,
    recovery: recoverySummary,
    outboxEvents,
    timeline: timeline(decision, challenges, recoverySummary, outboxEvents),
    sections: {
      challenge:
        scenario === "2" || scenario === "3"
          ? "UNAVAILABLE"
          : challenges.length > 0
            ? "AVAILABLE"
            : "NOT_APPLICABLE",
      recovery:
        scenario === "3"
          ? "UNAVAILABLE"
          : recoverySummary
            ? "AVAILABLE"
            : "NOT_APPLICABLE",
      outbox: unavailable ? "UNAVAILABLE" : "AVAILABLE",
    },
    partial,
    source: "fixtures",
  };
}
