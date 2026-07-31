import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { OutboxOperatorConsole } from "./outbox-console";

const mocks = vi.hoisted(() => ({
  search: vi.fn(),
}));

vi.mock("./outbox-browser", () => ({
  OutboxBrowserError: class OutboxBrowserError extends Error {
    constructor(
      readonly code: string,
      readonly status: number,
      readonly retryable: boolean,
    ) {
      super("Outbox search failed.");
    }
  },
  searchOutboxThroughBff: mocks.search,
}));

function health(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    pendingCount: 1,
    retryingCount: 1,
    inProgressCount: 1,
    deadLetteredCount: 1,
    oldestPendingAgeSeconds: 45,
    recentlyDeadLetteredCount: 1,
    recentlyPublishedCount: 2,
    windowMinutes: 15,
    asOf: "2026-07-31T10:00:00.000Z",
    ...overrides,
  };
}

const RECORDS = [
  {
    eventId: "00000000-0000-4000-c000-000000000001",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "PENDING",
    attemptCount: 0,
    occurredAt: "2026-07-31T09:59:00.000Z",
    publishedAt: null,
    deadLetteredAt: null,
    nextAttemptAt: "2026-07-31T09:59:00.000Z",
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••1111",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-c000-000000000002",
    aggregateType: "Recovery",
    eventType: "RECOVERY_MANUAL_REVIEW_REQUIRED",
    status: "PENDING",
    attemptCount: 2,
    occurredAt: "2026-07-31T09:40:00.000Z",
    publishedAt: null,
    deadLetteredAt: null,
    nextAttemptAt: "2026-07-31T10:05:00.000Z",
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••2222",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-c000-000000000003",
    aggregateType: "ProtectionDecision",
    eventType: "PROTECTION_DECISION_MADE",
    status: "PUBLISHED",
    attemptCount: 1,
    occurredAt: "2026-07-31T09:30:00.000Z",
    publishedAt: "2026-07-31T09:30:02.000Z",
    deadLetteredAt: null,
    nextAttemptAt: null,
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••3333",
    deadLetterReasonAvailable: false,
    deadLetterFailureCategory: null,
  },
  {
    eventId: "00000000-0000-4000-c000-000000000004",
    aggregateType: "Recovery",
    eventType: "RECOVERY_COMPLETED",
    status: "DEAD_LETTERED",
    attemptCount: 5,
    occurredAt: "2026-07-31T06:00:00.000Z",
    publishedAt: null,
    deadLetteredAt: "2026-07-31T06:12:00.000Z",
    nextAttemptAt: null,
    claimed: false,
    claimedAt: null,
    schemaVersion: "integration-event-1.0",
    maskedCorrelationReference: "••••4444",
    deadLetterReasonAvailable: true,
    deadLetterFailureCategory: "ConnectException",
  },
];

function page(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    health: health(),
    events: { records: RECORDS, nextCursor: undefined, pageSize: 25, hasMore: false, ...overrides },
    source: "fixtures",
  };
}

describe("OutboxOperatorConsole", () => {
  it("renders health metrics and the event table with pending, retrying, published, and dead-lettered rows", async () => {
    mocks.search.mockResolvedValueOnce(page());
    render(<OutboxOperatorConsole />);

    expect(await screen.findByText("4 events")).toBeVisible();
    const table = screen.getByRole("group", { name: "Outbox delivery records" });
    expect(within(table).getByText("Queued")).toBeVisible();
    expect(within(table).getByText("Retrying")).toBeVisible();
    expect(within(table).getByText("Published")).toBeVisible();
    expect(within(table).getByText("Dead-lettered")).toBeVisible();
    expect(within(table).getByText("ConnectException")).toBeVisible();
    expect(within(table).getByLabelText(/Masked correlation reference: ••••4444/)).toBeVisible();
  });

  it("renders an unauthorized state when the initial load is rejected", async () => {
    const { OutboxBrowserError } = (await import("./outbox-browser")) as unknown as {
      OutboxBrowserError: new (code: string, status: number, retryable: boolean) => Error;
    };
    mocks.search.mockRejectedValueOnce(new OutboxBrowserError("UNAUTHORIZED", 401, false));
    render(<OutboxOperatorConsole />);

    expect(await screen.findByText("Operator authentication is required")).toBeVisible();
  });

  it("keeps showing the last successful data with a stale banner when a refresh fails", async () => {
    const { OutboxBrowserError } = (await import("./outbox-browser")) as unknown as {
      OutboxBrowserError: new (code: string, status: number, retryable: boolean) => Error;
    };
    mocks.search.mockResolvedValueOnce(page());
    render(<OutboxOperatorConsole />);
    expect(await screen.findByText("4 events")).toBeVisible();

    mocks.search.mockRejectedValueOnce(new OutboxBrowserError("UPSTREAM_UNAVAILABLE", 503, true));
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    expect(await screen.findByText("Metrics may be stale")).toBeVisible();
    expect(screen.getByText("4 events")).toBeVisible();
  });

  it("renders an empty state when no records match the filters", async () => {
    mocks.search.mockResolvedValueOnce(page({ records: [] }));
    render(<OutboxOperatorConsole />);

    expect(await screen.findByText("No outbox events match this search")).toBeVisible();
  });
});
