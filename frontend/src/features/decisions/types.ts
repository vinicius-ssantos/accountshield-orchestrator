export const DecisionEventTypeValues = [
  "LOGIN_ATTEMPT",
  "SENSITIVE_ACTION",
  "LOGIN_RECOVERY_ATTEMPT",
  "PASSWORD_RESET_ATTEMPT",
  "CREDENTIAL_CHANGE_ATTEMPT",
  "DEVICE_TRUST_RESET_ATTEMPT",
] as const;

export type DecisionEventType = (typeof DecisionEventTypeValues)[number];

export const DecisionOutcomeValues = [
  "ALLOW",
  "REQUIRE_STEP_UP",
  "START_RECOVERY",
  "TEMPORARILY_BLOCK",
] as const;

export type DecisionOutcome = (typeof DecisionOutcomeValues)[number];

export const DecisionRiskBandValues = ["LOW", "MEDIUM", "HIGH"] as const;
export type DecisionRiskBand = (typeof DecisionRiskBandValues)[number];

export interface DecisionSummary {
  decisionReference: string;
  correlationId: string;
  eventType: DecisionEventType;
  riskScore: number;
  riskBand: DecisionRiskBand;
  outcome: DecisionOutcome;
  policyKey: string;
  policyVersion: string;
  decidedAt: string;
  degraded: boolean;
  simulated: boolean;
  provenanceAvailable: boolean;
}

export interface DecisionSearchCriteria {
  correlationId?: string;
  eventType?: DecisionEventType;
  outcome?: DecisionOutcome;
  riskBand?: DecisionRiskBand;
  policyVersion?: string;
  decidedFrom?: string;
  decidedTo?: string;
  cursor?: string;
  pageSize?: number;
}

export interface DecisionSearchPage {
  decisions: readonly DecisionSummary[];
  nextCursor?: string;
  pageSize: number;
  hasMore: boolean;
  source: "fixtures" | "live";
  partial: boolean;
}

export interface OperationsMetric {
  label: string;
  value: string;
  detail: string;
}
