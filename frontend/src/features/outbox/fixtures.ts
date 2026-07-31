import type { OutboxDataSource } from "./data-source";
import type { OutboxEventRecord, OutboxHealthSummary, OutboxSearchResult, OutboxStatus } from "./types";

const RECORDS: readonly OutboxEventRecord[] = [
  {
    eventId: "00000000-0000-4000-b000-000000000001",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "PENDING",
    attemptCount: 0,
    occurredAt: "2026-07-30T09:58:00.000Z",
    publishedAt: null,
    deadLetteredAt: null,
    nextAttemptAt: "2026-07-30T09:58:00.000Z",
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••1a2b",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-b000-000000000002",
    aggregateType: "Recovery",
    eventType: "RECOVERY_MANUAL_REVIEW_REQUIRED",
    status: "PENDING",
    attemptCount: 2,
    occurredAt: "2026-07-30T09:40:00.000Z",
    publishedAt: null,
    deadLetteredAt: null,
    nextAttemptAt: "2026-07-30T10:02:00.000Z",
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••3c4d",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-b000-000000000003",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "IN_PROGRESS",
    attemptCount: 1,
    occurredAt: "2026-07-30T09:59:40.000Z",
    publishedAt: null,
    deadLetteredAt: null,
    nextAttemptAt: "2026-07-30T09:59:40.000Z",
    claimed: true,
    claimedAt: "2026-07-30T09:59:55.000Z",
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••5e6f",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-b000-000000000004",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "PUBLISHED",
    attemptCount: 1,
    occurredAt: "2026-07-30T09:30:00.000Z",
    publishedAt: "2026-07-30T09:30:02.000Z",
    deadLetteredAt: null,
    nextAttemptAt: null,
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••7a8b",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-b000-000000000005",
    aggregateType: "AuditChain",
    eventType: "AUDIT_INTEGRITY_FAILED",
    status: "PUBLISHED",
    attemptCount: 1,
    occurredAt: "2026-07-30T08:15:00.000Z",
    publishedAt: "2026-07-30T08:15:03.000Z",
    deadLetteredAt: null,
    nextAttemptAt: null,
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••9c0d",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-b000-000000000006",
    aggregateType: "Recovery",
    eventType: "RECOVERY_COMPLETED",
    status: "DEAD_LETTERED",
    attemptCount: 5,
    occurredAt: "2026-07-30T06:00:00.000Z",
    publishedAt: null,
    deadLetteredAt: "2026-07-30T06:12:00.000Z",
    nextAttemptAt: null,
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••1e2f",
    deadLetterReasonAvailable: true,
    deadLetterFailureCategory: "ConnectException",
  },
  {
    eventId: "00000000-0000-4000-b000-000000000007",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "DEAD_LETTERED",
    attemptCount: 5,
    occurredAt: "2026-07-29T22:00:00.000Z",
    publishedAt: null,
    deadLetteredAt: "2026-07-29T22:11:00.000Z",
    nextAttemptAt: null,
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••3a4b",
    // Predates the last_error_category write path (ADR 0045) -- a real, historical, honest gap,
    // not a fabricated reason.
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-b000-000000000008",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "PENDING",
    attemptCount: 0,
    occurredAt: "2026-07-30T09:59:58.000Z",
    publishedAt: null,
    deadLetteredAt: null,
    nextAttemptAt: "2026-07-30T09:59:58.000Z",
    claimed: false,
    claimedAt: null,
    schemaVersion: null,
    maskedCorrelationReference: "••••5c6d",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
];

const HEALTH: OutboxHealthSummary = {
  pendingCount: RECORDS.filter((record) => record.status === "PENDING" && record.attemptCount === 0).length,
  retryingCount: RECORDS.filter((record) => record.status === "PENDING" && record.attemptCount > 0).length,
  inProgressCount: RECORDS.filter((record) => record.status === "IN_PROGRESS").length,
  deadLetteredCount: RECORDS.filter((record) => record.status === "DEAD_LETTERED").length,
  oldestPendingAgeSeconds: 120,
  recentlyDeadLetteredCount: 1,
  recentlyPublishedCount: 2,
  windowMinutes: 15,
  asOf: "2026-07-30T10:00:00.000Z",
};

function matches(record: OutboxEventRecord, filters: {
  statuses?: readonly OutboxStatus[];
  eventType?: string;
  occurredFrom?: string;
  occurredTo?: string;
  minAttemptCount?: number;
  maxAttemptCount?: number;
}): boolean {
  if (filters.statuses && filters.statuses.length > 0 && !filters.statuses.includes(record.status)) return false;
  if (filters.eventType && record.eventType !== filters.eventType) return false;
  if (filters.occurredFrom && record.occurredAt < filters.occurredFrom) return false;
  if (filters.occurredTo && record.occurredAt >= filters.occurredTo) return false;
  if (filters.minAttemptCount !== undefined && record.attemptCount < filters.minAttemptCount) return false;
  if (filters.maxAttemptCount !== undefined && record.attemptCount > filters.maxAttemptCount) return false;
  return true;
}

export const fixtureOutboxDataSource: OutboxDataSource = {
  async search(filters): Promise<OutboxSearchResult> {
    const sorted = [...RECORDS].sort((a, b) => (a.occurredAt < b.occurredAt ? 1 : -1));
    const filtered = sorted.filter((record) => matches(record, filters));

    const pageSize = filters.pageSize ?? 25;
    const startIndex = filters.cursor ? Number.parseInt(filters.cursor, 10) : 0;
    const offset = Number.isInteger(startIndex) && startIndex >= 0 ? startIndex : 0;
    const page = filtered.slice(offset, offset + pageSize);
    const hasMore = offset + pageSize < filtered.length;

    return {
      health: HEALTH,
      events: {
        records: page,
        nextCursor: hasMore ? String(offset + pageSize) : undefined,
        pageSize,
        hasMore,
      },
      source: "fixtures",
    };
  },
};
