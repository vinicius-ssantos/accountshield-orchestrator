export const PolicyLifecycleStatusValues = [
  "DRAFT",
  "VALIDATED",
  "APPROVED",
  "ACTIVE",
  "RETIRED",
  "REJECTED",
] as const;

export type PolicyLifecycleStatus = (typeof PolicyLifecycleStatusValues)[number];

export const PolicyRolloutLifecycleStatusValues = ["ACTIVE", "ROLLED_BACK"] as const;
export type PolicyRolloutLifecycleStatus = (typeof PolicyRolloutLifecycleStatusValues)[number];

export const PolicyDiagnosticSeverityValues = ["ERROR", "WARNING"] as const;
export type PolicyDiagnosticSeverity = (typeof PolicyDiagnosticSeverityValues)[number];

export const PolicyImpactAvailabilityValues = ["AVAILABLE", "NOT_APPLICABLE", "UNAVAILABLE"] as const;
export type PolicyImpactAvailability = (typeof PolicyImpactAvailabilityValues)[number];

export interface PolicyDirectorySummary {
  policyKey: string;
  totalVersions: number;
  activeVersion: string | null;
  activeVersionActivatedAt: string | null;
  hasActiveRollout: boolean;
}

export interface PolicyDirectoryPage {
  policies: readonly PolicyDirectorySummary[];
  source: "fixtures" | "live";
}

export interface PolicyDiagnostic {
  code: string;
  severity: PolicyDiagnosticSeverity;
  path: string;
  message: string;
}

export interface PolicyAnalysis {
  analyzerVersion: string;
  diagnostics: readonly PolicyDiagnostic[];
}

export interface PolicyGovernance {
  createdBy: string | null;
  validatedBy: string | null;
  validatedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  approvalReason: string | null;
}

export interface PolicyVersionSummary {
  id: string;
  policyKey: string;
  version: string;
  status: PolicyLifecycleStatus;
  allowMaxScore: number | null;
  stepUpMaxScore: number | null;
  recoveryMaxScore: number | null;
  createdAt: string;
  activatedAt: string | null;
  analysis: PolicyAnalysis | null;
  governance: PolicyGovernance | null;
}

export interface PolicyRoutingScopeEntry {
  clientId: string;
  eventType: string;
}

export interface PolicyRolloutSummary {
  candidateVersion: string;
  rolloutPercentage: number;
  status: PolicyRolloutLifecycleStatus;
  startedAt: string;
  startedBy: string;
  updatedAt: string;
  rolledBackAt: string | null;
  rolledBackBy: string | null;
}

export interface PolicyReasonEvidence {
  code: string;
  contribution: number;
}

export interface PolicyDivergentDecision {
  maskedProtectionRequestReference: string;
  redactedAccountReference: string;
  originalOutcome: string;
  candidateOutcome: string;
  riskScore: number;
  originalReasons: readonly PolicyReasonEvidence[];
}

export interface PolicySegmentImpact {
  segment: string;
  totalDecisions: number;
  divergentDecisions: number;
}

export interface PolicyImpactSummary {
  candidatePolicyVersion: string;
  originalPolicyVersionsObserved: readonly string[];
  algorithmVersionsObserved: readonly string[];
  totalDecisions: number;
  divergentDecisionsCount: number;
  divergencePercentage: number;
  maxDivergencePercentageThreshold: number;
  exceedsDivergenceThreshold: boolean;
  transitionMatrix: Readonly<Record<string, Readonly<Record<string, number>>>>;
  impactByEventType: Readonly<Record<string, PolicySegmentImpact>>;
  impactByRiskBand: Readonly<Record<string, PolicySegmentImpact>>;
  divergentDecisions: readonly PolicyDivergentDecision[];
}

export interface PolicyInvestigationDetail {
  policyKey: string;
  versions: readonly PolicyVersionSummary[];
  routingScope: readonly PolicyRoutingScopeEntry[];
  activeRollout: PolicyRolloutSummary | null;
  impactAnalysis: PolicyImpactSummary | null;
  impactAvailability: PolicyImpactAvailability;
  source: "fixtures" | "live";
}
