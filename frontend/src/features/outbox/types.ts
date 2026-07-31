export const OutboxStatusValues = ["PENDING", "IN_PROGRESS", "PUBLISHED", "DEAD_LETTERED"] as const;
export type OutboxStatus = (typeof OutboxStatusValues)[number];

export interface OutboxHealthSummary {
  pendingCount: number;
  retryingCount: number;
  inProgressCount: number;
  deadLetteredCount: number;
  oldestPendingAgeSeconds: number | null;
  recentlyDeadLetteredCount: number;
  recentlyPublishedCount: number;
  windowMinutes: number;
  asOf: string;
}

export interface OutboxEventRecord {
  eventId: string;
  aggregateType: string;
  eventType: string;
  status: OutboxStatus;
  attemptCount: number;
  occurredAt: string;
  publishedAt: string | null;
  deadLetteredAt: string | null;
  nextAttemptAt: string | null;
  claimed: boolean;
  claimedAt: string | null;
  schemaVersion: string | null;
  maskedCorrelationReference: string;
  deadLetterReasonAvailable: boolean;
  deadLetterFailureCategory: string | null;
}

export interface OutboxEventPage {
  records: readonly OutboxEventRecord[];
  nextCursor?: string;
  pageSize: number;
  hasMore: boolean;
}

export interface OutboxSearchFilters {
  statuses?: readonly OutboxStatus[];
  eventType?: string;
  occurredFrom?: string;
  occurredTo?: string;
  minAttemptCount?: number;
  maxAttemptCount?: number;
  cursor?: string;
  pageSize?: number;
}

export interface OutboxSearchResult {
  health: OutboxHealthSummary;
  events: OutboxEventPage;
  source: "fixtures" | "live";
}
