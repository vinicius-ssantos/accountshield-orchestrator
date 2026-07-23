import type { DecisionsDataSource } from "./data-source";
import type { DecisionSummary, OperationsMetric } from "./types";

const metrics: readonly OperationsMetric[] = [
  { label: "Decisions today", value: "1,284", detail: "Synthetic fixture data" },
  { label: "High-risk events", value: "37", detail: "2.9% of evaluated events" },
  { label: "Manual reviews", value: "12", detail: "Read-only until RBAC is ready" },
  { label: "Replay divergences", value: "3", detail: "Candidate policy comparison" },
];

const decisions: readonly DecisionSummary[] = [
  {
    correlationId: "cor_8f12…",
    eventType: "LOGIN",
    riskScore: 82,
    outcome: "START_RECOVERY",
    policyVersion: "v7",
  },
  {
    correlationId: "cor_a921…",
    eventType: "PASSWORD_CHANGE",
    riskScore: 64,
    outcome: "TEMPORARILY_BLOCK",
    policyVersion: "v7",
  },
  {
    correlationId: "cor_120c…",
    eventType: "LOGIN",
    riskScore: 41,
    outcome: "REQUIRE_STEP_UP",
    policyVersion: "v7",
  },
  {
    correlationId: "cor_77bd…",
    eventType: "SENSITIVE_ACTION",
    riskScore: 18,
    outcome: "ALLOW",
    policyVersion: "v7",
  },
];

export const fixtureDecisionsDataSource: DecisionsDataSource = {
  async listRecent() {
    return decisions;
  },
  async listOverviewMetrics() {
    return metrics;
  },
};
