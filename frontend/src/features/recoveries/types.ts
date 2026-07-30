export const RecoveryStatusValues = [
  "INITIATED",
  "VERIFYING_IDENTITY",
  "IDENTITY_VERIFIED",
  "DELAYED",
  "MANUAL_REVIEW",
  "COMPLETED",
  "IDENTITY_FAILED",
  "REJECTED",
  "ABORTED",
] as const;

export type RecoveryStatus = (typeof RecoveryStatusValues)[number];

export const RecoveryClassificationValues = [
  "IMMEDIATE",
  "DELAYED",
  "MANUAL_REVIEW",
] as const;

export type RecoveryClassification = (typeof RecoveryClassificationValues)[number];

export const RecoveryEventTypeValues = [
  "LOGIN",
  "PASSWORD_RESET",
  "CREDENTIAL_CHANGE",
  "DEVICE_TRUST_RESET",
] as const;

export type RecoveryEventType = (typeof RecoveryEventTypeValues)[number];

export const RecoveryReviewStateValues = [
  "PENDING",
  "REVIEWED",
  "NOT_APPLICABLE",
] as const;

export type RecoveryReviewState = (typeof RecoveryReviewStateValues)[number];

export const RecoverySectionAvailabilityValues = [
  "AVAILABLE",
  "NOT_APPLICABLE",
  "UNAVAILABLE",
] as const;

export type RecoverySectionAvailability = (typeof RecoverySectionAvailabilityValues)[number];

export interface RecoverySummary {
  recoveryReference: string;
  maskedSubjectReference: string;
  eventType: RecoveryEventType;
  status: RecoveryStatus;
  terminal: boolean;
  classification: RecoveryClassification;
  classificationRuleVersion: string;
  riskScore: number;
  initiatedAt: string;
  updatedAt: string;
  eligibleAfter: string | null;
  originatingDecisionReference: string;
  reviewState: RecoveryReviewState;
  challengeExpected: boolean;
}

export interface RecoverySearchCriteria {
  status?: RecoveryStatus;
  classification?: RecoveryClassification;
  eventType?: RecoveryEventType;
  reviewState?: RecoveryReviewState;
  initiatedFrom?: string;
  initiatedTo?: string;
  eligibleFrom?: string;
  eligibleTo?: string;
  minimumRiskScore?: number;
  maximumRiskScore?: number;
  cursor?: string;
  pageSize?: number;
}

export interface RecoverySearchPage {
  recoveries: readonly RecoverySummary[];
  nextCursor?: string;
  pageSize: number;
  hasMore: boolean;
  source: "fixtures" | "live";
  partial: boolean;
}

export interface RecoveryChallengeEvidence {
  reference: string;
  challengeType: string;
  purpose: string;
  status: string;
  createdAt: string;
  expiresAt: string;
  consumedAt: string | null;
}

export interface RecoveryInvestigationDetail {
  recovery: RecoverySummary;
  protectionRequestReference: string;
  reviewerPresent: boolean;
  challenges: readonly RecoveryChallengeEvidence[];
  challengeAvailability: RecoverySectionAvailability;
  partial: boolean;
  source: "fixtures" | "live";
}
