import type { DecisionsDataSource } from "./data-source";
import type {
  DecisionSearchCriteria,
  DecisionSummary,
  OperationsMetric,
} from "./types";

const metrics: readonly OperationsMetric[] = [
  { label: "Decisions today", value: "1,284", detail: "Synthetic fixture data" },
  { label: "High-risk events", value: "37", detail: "2.9% of evaluated events" },
  { label: "Degraded decisions", value: "12", detail: "Dependency fallback exercised" },
  { label: "Provenance gaps", value: "3", detail: "Queued for operator investigation" },
];

const decisions: readonly DecisionSummary[] = [
  {
    decisionReference: "dec_fixture_01",
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
  {
    decisionReference: "dec_fixture_02",
    correlationId: "corr_demo_password_a921",
    eventType: "CREDENTIAL_CHANGE_ATTEMPT",
    riskScore: 64,
    riskBand: "MEDIUM",
    outcome: "TEMPORARILY_BLOCK",
    policyKey: "account-protection",
    policyVersion: "v7",
    decidedAt: "2026-07-29T04:31:00.000Z",
    degraded: true,
    simulated: false,
    provenanceAvailable: true,
  },
  {
    decisionReference: "dec_fixture_03",
    correlationId: "corr_demo_travel_120c",
    eventType: "LOGIN_ATTEMPT",
    riskScore: 41,
    riskBand: "MEDIUM",
    outcome: "REQUIRE_STEP_UP",
    policyKey: "account-protection",
    policyVersion: "v7",
    decidedAt: "2026-07-29T04:24:00.000Z",
    degraded: false,
    simulated: false,
    provenanceAvailable: false,
  },
  {
    decisionReference: "dec_fixture_04",
    correlationId: "corr_demo_action_77bd",
    eventType: "SENSITIVE_ACTION",
    riskScore: 18,
    riskBand: "LOW",
    outcome: "ALLOW",
    policyKey: "account-protection",
    policyVersion: "v7",
    decidedAt: "2026-07-29T04:17:00.000Z",
    degraded: false,
    simulated: false,
    provenanceAvailable: true,
  },
  {
    decisionReference: "dec_fixture_05",
    correlationId: "corr_demo_recovery_ef31",
    eventType: "LOGIN_RECOVERY_ATTEMPT",
    riskScore: 71,
    riskBand: "HIGH",
    outcome: "START_RECOVERY",
    policyKey: "account-recovery",
    policyVersion: "v4",
    decidedAt: "2026-07-29T04:10:00.000Z",
    degraded: false,
    simulated: true,
    provenanceAvailable: true,
  },
  {
    decisionReference: "dec_fixture_06",
    correlationId: "corr_demo_device_b139",
    eventType: "DEVICE_TRUST_RESET_ATTEMPT",
    riskScore: 53,
    riskBand: "MEDIUM",
    outcome: "REQUIRE_STEP_UP",
    policyKey: "device-trust",
    policyVersion: "v2",
    decidedAt: "2026-07-29T04:03:00.000Z",
    degraded: false,
    simulated: false,
    provenanceAvailable: true,
  },
];

function matches(decision: DecisionSummary, criteria: DecisionSearchCriteria): boolean {
  const correlation = criteria.correlationId?.trim().toLowerCase();
  return (
    (!correlation || decision.correlationId.toLowerCase() === correlation) &&
    (!criteria.eventType || decision.eventType === criteria.eventType) &&
    (!criteria.outcome || decision.outcome === criteria.outcome) &&
    (!criteria.riskBand || decision.riskBand === criteria.riskBand) &&
    (!criteria.policyVersion || decision.policyVersion === criteria.policyVersion.trim()) &&
    (!criteria.decidedFrom || decision.decidedAt >= criteria.decidedFrom) &&
    (!criteria.decidedTo || decision.decidedAt <= criteria.decidedTo)
  );
}

function cursorOffset(cursor: string | undefined): number {
  if (!cursor) return 0;
  const match = /^fixture-(\d+)$/.exec(cursor);
  return match ? Number.parseInt(match[1], 10) : 0;
}

export const fixtureDecisionsDataSource: DecisionsDataSource = {
  async search(criteria) {
    const pageSize = Math.min(Math.max(criteria.pageSize ?? 25, 1), 100);
    const filtered = decisions.filter((decision) => matches(decision, criteria));
    const offset = cursorOffset(criteria.cursor);
    const page = filtered.slice(offset, offset + pageSize);
    const nextOffset = offset + page.length;
    const hasMore = nextOffset < filtered.length;

    return {
      decisions: page,
      nextCursor: hasMore ? `fixture-${nextOffset}` : undefined,
      pageSize,
      hasMore,
      source: "fixtures",
      partial: false,
    };
  },
  async listRecent() {
    return decisions.slice(0, 4);
  },
  async listOverviewMetrics() {
    return metrics;
  },
};
