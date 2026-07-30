import type { DecisionReplayComparison, DecisionSummary } from "./types";

export function buildFixtureDecisionReplay(decision: DecisionSummary): DecisionReplayComparison {
  const scenario = decision.decisionReference.slice(-1);

  if (scenario === "3") {
    throw new Error("Decision replay fixture is unavailable for this historical decision.");
  }

  const original = {
    outcome: decision.outcome,
    riskScore: decision.riskScore,
    riskBand: decision.riskBand,
    reasons: [
      { code: "NEW_DEVICE", contribution: 22 },
      { code: "NETWORK_RISK", contribution: 18 },
    ],
  };

  const diverges = scenario === "2";
  const replayed = diverges
    ? {
        outcome: "REQUIRE_STEP_UP",
        riskScore: Math.max(0, decision.riskScore - 6),
        riskBand: "MEDIUM",
        reasons: [{ code: "NEW_DEVICE", contribution: 22 }],
      }
    : original;

  const mismatches = diverges
    ? [
        `riskScore: expected ${original.riskScore} but replay produced ${replayed.riskScore}`,
        `outcome: expected ${original.outcome} but replay produced ${replayed.outcome}`,
        "reasons: expected [NEW_DEVICE(+22), NETWORK_RISK(+18)] but replay produced [NEW_DEVICE(+22)]",
      ]
    : [];

  return {
    decisionReference: decision.decisionReference,
    maskedSubjectReference: `acct••••${scenario.padStart(4, "0")}`,
    matches: !diverges,
    original,
    replayed,
    policyKey: decision.policyKey,
    policyVersion: decision.policyVersion,
    algorithmVersion: "risk-score-v3",
    normalizedInputSchemaVersion: "protection-event.v2",
    reasonCatalogVersion: "reasons.v4",
    decisionEngineVersion: "engine.v3",
    mismatches,
    source: "fixtures",
  };
}
