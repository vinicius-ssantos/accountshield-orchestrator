export type DecisionOutcome =
  | "ALLOW"
  | "REQUIRE_STEP_UP"
  | "START_RECOVERY"
  | "TEMPORARILY_BLOCK";

export interface DecisionSummary {
  correlationId: string;
  eventType: string;
  riskScore: number;
  outcome: DecisionOutcome;
  policyVersion: string;
}

export interface OperationsMetric {
  label: string;
  value: string;
  detail: string;
}
