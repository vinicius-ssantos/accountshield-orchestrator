import type { RecoveriesDataSource } from "./data-source";
import type {
  RecoveryChallengeEvidence,
  RecoveryInvestigationDetail,
  RecoverySearchCriteria,
  RecoverySummary,
} from "./types";

function plusMinutes(instant: string, minutes: number): string {
  return new Date(new Date(instant).valueOf() + minutes * 60_000).toISOString();
}

const recoveries: readonly RecoverySummary[] = [
  {
    recoveryReference: "00000000-0000-4000-9000-000000000001",
    maskedSubjectReference: "••••7f21",
    eventType: "LOGIN",
    status: "INITIATED",
    terminal: false,
    classification: "IMMEDIATE",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 12,
    initiatedAt: "2026-07-30T09:12:00.000Z",
    updatedAt: "2026-07-30T09:12:00.000Z",
    eligibleAfter: null,
    originatingDecisionReference: "••••a001",
    reviewState: "NOT_APPLICABLE",
    challengeExpected: true,
  },
  {
    recoveryReference: "00000000-0000-4000-9000-000000000002",
    maskedSubjectReference: "••••3c88",
    eventType: "PASSWORD_RESET",
    status: "DELAYED",
    terminal: false,
    classification: "DELAYED",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 45,
    initiatedAt: "2026-07-30T08:40:00.000Z",
    updatedAt: "2026-07-30T08:41:00.000Z",
    eligibleAfter: "2026-07-30T10:41:00.000Z",
    originatingDecisionReference: "••••a002",
    reviewState: "PENDING",
    challengeExpected: true,
  },
  {
    recoveryReference: "00000000-0000-4000-9000-000000000003",
    maskedSubjectReference: "••••90de",
    eventType: "CREDENTIAL_CHANGE",
    status: "MANUAL_REVIEW",
    terminal: false,
    classification: "MANUAL_REVIEW",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 78,
    initiatedAt: "2026-07-30T07:55:00.000Z",
    updatedAt: "2026-07-30T08:02:00.000Z",
    eligibleAfter: null,
    originatingDecisionReference: "••••a003",
    reviewState: "PENDING",
    challengeExpected: true,
  },
  {
    recoveryReference: "00000000-0000-4000-9000-000000000004",
    maskedSubjectReference: "••••11bb",
    eventType: "LOGIN",
    status: "COMPLETED",
    terminal: true,
    classification: "IMMEDIATE",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 8,
    initiatedAt: "2026-07-30T06:10:00.000Z",
    updatedAt: "2026-07-30T06:14:00.000Z",
    eligibleAfter: null,
    originatingDecisionReference: "••••a004",
    reviewState: "NOT_APPLICABLE",
    challengeExpected: true,
  },
  {
    recoveryReference: "00000000-0000-4000-9000-000000000005",
    maskedSubjectReference: "••••5e6a",
    eventType: "DEVICE_TRUST_RESET",
    status: "IDENTITY_FAILED",
    terminal: true,
    classification: "DELAYED",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 52,
    initiatedAt: "2026-07-30T05:30:00.000Z",
    updatedAt: "2026-07-30T05:45:00.000Z",
    eligibleAfter: null,
    originatingDecisionReference: "••••a005",
    reviewState: "NOT_APPLICABLE",
    challengeExpected: true,
  },
  {
    recoveryReference: "00000000-0000-4000-9000-000000000006",
    maskedSubjectReference: "••••c204",
    eventType: "CREDENTIAL_CHANGE",
    status: "REJECTED",
    terminal: true,
    classification: "MANUAL_REVIEW",
    classificationRuleVersion: "recovery-classification-1.0",
    riskScore: 91,
    initiatedAt: "2026-07-30T04:05:00.000Z",
    updatedAt: "2026-07-30T04:20:00.000Z",
    eligibleAfter: null,
    originatingDecisionReference: "••••a006",
    reviewState: "REVIEWED",
    challengeExpected: true,
  },
];

function challenge(
  summary: RecoverySummary,
  status: string,
): RecoveryChallengeEvidence {
  return {
    reference: `challenge-${summary.recoveryReference.slice(-6)}`,
    challengeType: "TOTP_SIMULATED",
    purpose: "RECOVERY_IDENTITY",
    status,
    createdAt: plusMinutes(summary.initiatedAt, 1),
    expiresAt: plusMinutes(summary.initiatedAt, 11),
    consumedAt: status === "CONSUMED" ? plusMinutes(summary.initiatedAt, 4) : null,
  };
}

function buildDetail(summary: RecoverySummary): RecoveryInvestigationDetail {
  const scenario = summary.recoveryReference.slice(-1);

  if (scenario === "3") {
    return {
      recovery: summary,
      protectionRequestReference: "••••b003",
      reviewerPresent: false,
      challenges: [],
      challengeAvailability: "UNAVAILABLE",
      partial: true,
      source: "fixtures",
    };
  }

  const status =
    scenario === "4" ? "CONSUMED" : scenario === "5" ? "EXPIRED" : "ISSUED";

  return {
    recovery: summary,
    protectionRequestReference: `••••b00${scenario}`,
    reviewerPresent: summary.reviewState === "REVIEWED",
    challenges: [challenge(summary, status)],
    challengeAvailability: "AVAILABLE",
    partial: false,
    source: "fixtures",
  };
}

function matches(summary: RecoverySummary, criteria: RecoverySearchCriteria): boolean {
  return (
    (!criteria.status || summary.status === criteria.status) &&
    (!criteria.classification || summary.classification === criteria.classification) &&
    (!criteria.eventType || summary.eventType === criteria.eventType) &&
    (!criteria.reviewState || summary.reviewState === criteria.reviewState) &&
    (!criteria.initiatedFrom || summary.initiatedAt >= criteria.initiatedFrom) &&
    (!criteria.initiatedTo || summary.initiatedAt <= criteria.initiatedTo) &&
    (criteria.minimumRiskScore === undefined || summary.riskScore >= criteria.minimumRiskScore) &&
    (criteria.maximumRiskScore === undefined || summary.riskScore <= criteria.maximumRiskScore)
  );
}

function cursorOffset(cursor: string | undefined): number {
  if (!cursor) return 0;
  const match = /^fixture-(\d+)$/.exec(cursor);
  return match ? Number.parseInt(match[1], 10) : 0;
}

export const fixtureRecoveriesDataSource: RecoveriesDataSource = {
  async search(criteria) {
    const pageSize = Math.min(Math.max(criteria.pageSize ?? 25, 1), 100);
    const filtered = recoveries.filter((summary) => matches(summary, criteria));
    const offset = cursorOffset(criteria.cursor);
    const page = filtered.slice(offset, offset + pageSize);
    const nextOffset = offset + page.length;
    const hasMore = nextOffset < filtered.length;

    return {
      recoveries: page,
      nextCursor: hasMore ? `fixture-${nextOffset}` : undefined,
      pageSize,
      hasMore,
      source: "fixtures",
      partial: false,
    };
  },
  async investigate(recoveryReference) {
    const summary = recoveries.find((item) => item.recoveryReference === recoveryReference);
    if (!summary) throw new Error("Recovery fixture was not found.");
    return buildDetail(summary);
  },
};
