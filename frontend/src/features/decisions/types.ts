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

export const SectionAvailabilityValues = [
  "AVAILABLE",
  "NOT_APPLICABLE",
  "UNAVAILABLE",
] as const;
export type SectionAvailability = (typeof SectionAvailabilityValues)[number];

export const SignalProvenanceStateValues = [
  "RECORDED",
  "SIMULATED",
  "STALE",
  "UNAVAILABLE",
] as const;
export type SignalProvenanceState = (typeof SignalProvenanceStateValues)[number];

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

export interface DecisionReasonEvidence {
  code: string;
  contribution: number;
  ordinal: number;
}

export interface DecisionSignalProvenance {
  provider: string | null;
  observedAt: string | null;
  confidence: string | null;
  schemaVersion: string | null;
  state: SignalProvenanceState;
  simulated: boolean;
  integrityAvailable: boolean;
}

export interface DecisionPolicyProvenance {
  policyKey: string;
  policyVersion: string;
  routingReason: string;
  rolloutCohortBucket: number | null;
  rolloutCandidateVersion: string | null;
  rolloutCandidateSelected: boolean | null;
}

export interface DecisionExecutionProvenance {
  algorithmVersion: string;
  normalizedInputSchemaVersion: string | null;
  reasonCatalogVersion: string | null;
  decisionEngineVersion: string | null;
  applicationCommitSha: string | null;
  canonicalInputHashAvailable: boolean;
  auditRecordHashAvailable: boolean;
}

export interface DecisionChallengeSummary {
  reference: string;
  challengeType: string;
  purpose: string;
  status: string;
  createdAt: string;
  expiresAt: string;
  consumedAt: string | null;
}

export interface DecisionRecoverySummary {
  reference: string;
  directive: string;
  status: string;
  issuedAt: string;
  expiresAt: string;
  consumedAt: string | null;
}

export interface DecisionOutboxSummary {
  reference: string;
  eventType: string;
  status: string;
  occurredAt: string;
  publishedAt: string | null;
  deadLetteredAt: string | null;
  attemptCount: number;
}

export interface DecisionTimelineEntry {
  reference: string;
  kind: string;
  status: string;
  occurredAt: string;
}

export interface DecisionInvestigationSections {
  challenge: SectionAvailability;
  recovery: SectionAvailability;
  outbox: SectionAvailability;
}

export interface DecisionInvestigationDetail {
  decision: DecisionSummary;
  maskedSubjectReference: string;
  reasons: readonly DecisionReasonEvidence[];
  signalProvenance: DecisionSignalProvenance;
  policyProvenance: DecisionPolicyProvenance;
  executionProvenance: DecisionExecutionProvenance;
  challenges: readonly DecisionChallengeSummary[];
  recovery: DecisionRecoverySummary | null;
  outboxEvents: readonly DecisionOutboxSummary[];
  timeline: readonly DecisionTimelineEntry[];
  sections: DecisionInvestigationSections;
  partial: boolean;
  source: "fixtures" | "live";
}

export interface OperationsMetric {
  label: string;
  value: string;
  detail: string;
}
