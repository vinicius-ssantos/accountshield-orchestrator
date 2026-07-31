"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";

import {
  ApplicationState,
  DataTable,
  FilterField,
  MaskedIdentifier,
  MetricCard,
  Panel,
  SafeAlert,
  SectionHeader,
  StatusBadge,
  Timestamp,
  type DataTableRow,
  type StatusTone,
} from "@/design-system/components";

import { OutboxBrowserError, searchOutboxThroughBff } from "./outbox-browser";
import { OutboxStatusValues } from "./types";
import type { OutboxSearchFilters, OutboxSearchResult, OutboxStatus } from "./types";

const DEFAULT_FILTERS: OutboxSearchFilters = { pageSize: 25 };

function optionalString(value: FormDataEntryValue | null): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim();
  return normalized || undefined;
}

function toInstant(value: FormDataEntryValue | null): string | undefined {
  const normalized = optionalString(value);
  if (!normalized) return undefined;
  const parsed = new Date(normalized);
  return Number.isNaN(parsed.valueOf()) ? undefined : parsed.toISOString();
}

function optionalInteger(value: FormDataEntryValue | null): number | undefined {
  const normalized = optionalString(value);
  if (!normalized) return undefined;
  const parsed = Number.parseInt(normalized, 10);
  return Number.isInteger(parsed) ? parsed : undefined;
}

function filtersFromForm(form: HTMLFormElement): OutboxSearchFilters {
  const data = new FormData(form);
  const statuses = data
    .getAll("statuses")
    .filter((value): value is string => typeof value === "string" && value !== "");
  const pageSize = Number.parseInt(String(data.get("pageSize") ?? "25"), 10);
  return {
    statuses: statuses.length > 0 ? (statuses as OutboxStatus[]) : undefined,
    eventType: optionalString(data.get("eventType")),
    occurredFrom: toInstant(data.get("occurredFrom")),
    occurredTo: toInstant(data.get("occurredTo")),
    minAttemptCount: optionalInteger(data.get("minAttemptCount")),
    maxAttemptCount: optionalInteger(data.get("maxAttemptCount")),
    pageSize: Number.isInteger(pageSize) ? pageSize : 25,
  };
}

function statusTone(status: OutboxStatus): StatusTone {
  if (status === "PUBLISHED") return "positive";
  if (status === "DEAD_LETTERED") return "critical";
  if (status === "PENDING") return "info";
  return "attention";
}

function statusLabel(status: OutboxStatus, attemptCount: number): string {
  if (status === "PENDING" && attemptCount > 0) return "Retrying";
  if (status === "PENDING") return "Queued";
  if (status === "IN_PROGRESS") return "Processing";
  if (status === "PUBLISHED") return "Published";
  return "Dead-lettered";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "unavailable" | "degraded";
  title: string;
  description: string;
} {
  if (error instanceof OutboxBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof OutboxBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Outbox access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof OutboxBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "The filter combination is invalid",
      description: "Review the filter values and time range. No sensitive diagnostic detail was exposed.",
    };
  }
  return {
    kind: "unavailable",
    title: "Outbox search is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

export function OutboxOperatorConsole() {
  const [filters, setFilters] = useState<OutboxSearchFilters>(DEFAULT_FILTERS);
  const [result, setResult] = useState<OutboxSearchResult>();
  const [loadError, setLoadError] = useState<unknown>();
  const [refreshError, setRefreshError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const requestSequence = useRef(0);
  const resultRef = useRef<OutboxSearchResult | undefined>(undefined);

  const runSearch = useCallback(async (next: OutboxSearchFilters) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    try {
      const page = await searchOutboxThroughBff(next);
      if (sequence === requestSequence.current) {
        setFilters(next);
        setResult(page);
        resultRef.current = page;
        setLoadError(undefined);
        setRefreshError(undefined);
      }
    } catch (searchError) {
      if (sequence === requestSequence.current) {
        if (resultRef.current) {
          setRefreshError(searchError);
        } else {
          setLoadError(searchError);
        }
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => void runSearch(DEFAULT_FILTERS));
  }, [runSearch]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void runSearch(filtersFromForm(event.currentTarget));
  }

  const failure = !result && loadError ? failureState(loadError) : undefined;

  const rows: readonly DataTableRow[] = (result?.events.records ?? []).map((record) => ({
    id: record.eventId,
    cells: {
      occurredAt: <Timestamp value={record.occurredAt} />,
      eventType: record.eventType.replaceAll("_", " "),
      aggregateType: record.aggregateType,
      status: (
        <span className="outboxStatusCell">
          <StatusBadge label={statusLabel(record.status, record.attemptCount)} tone={statusTone(record.status)} />
          {record.attemptCount > 0 ? <span className="muted"> attempt {record.attemptCount}</span> : null}
        </span>
      ),
      correlation: (
        <MaskedIdentifier label="Masked correlation reference" maskedValue={record.maskedCorrelationReference} />
      ),
      claimed: record.claimed ? (
        record.claimedAt ? (
          <Timestamp label="Claimed at" value={record.claimedAt} />
        ) : (
          <span>Claimed</span>
        )
      ) : (
        <span className="muted">Not claimed</span>
      ),
      nextAttempt: record.nextAttemptAt ? (
        <Timestamp label="Next attempt at" value={record.nextAttemptAt} />
      ) : (
        <span className="muted">Not applicable</span>
      ),
      outcome: record.publishedAt ? (
        <Timestamp label="Published at" value={record.publishedAt} />
      ) : record.deadLetteredAt ? (
        <Timestamp label="Dead-lettered at" value={record.deadLetteredAt} />
      ) : (
        <span className="muted">In flight</span>
      ),
      reason:
        record.status === "DEAD_LETTERED" ? (
          record.deadLetterReasonAvailable && record.deadLetterFailureCategory ? (
            <StatusBadge label={record.deadLetterFailureCategory} tone="critical" />
          ) : (
            <span className="muted">Reason unavailable</span>
          )
        ) : (
          <span className="muted">Not applicable</span>
        ),
    },
  }));

  return (
    <>
      <Panel>
        <SectionHeader
          eyebrow="Authorized read surface"
          title="Delivery health"
          description={
            result
              ? `As of ${new Date(result.health.asOf).toLocaleString()}. Window: last ${result.health.windowMinutes} minutes. Source: ${result.source}.`
              : "Aggregate outbox delivery health for the operations team."
          }
          trailing={
            result ? (
              <button className="actionLink" disabled={loading} onClick={() => void runSearch(filters)} type="button">
                Refresh
              </button>
            ) : undefined
          }
        />
        {result ? (
          <section aria-label="Outbox health metrics" className="metricGrid">
            <MetricCard detail="Never attempted" label="Pending" value={String(result.health.pendingCount)} />
            <MetricCard
              detail="Backed off, waiting for next attempt"
              label="Retrying"
              value={String(result.health.retryingCount)}
            />
            <MetricCard
              detail="Currently claimed"
              label="In progress"
              value={String(result.health.inProgressCount)}
            />
            <MetricCard detail="All time" label="Dead-lettered" value={String(result.health.deadLetteredCount)} />
            <MetricCard
              detail={result.health.oldestPendingAgeSeconds === null ? "No pending events" : "Age of the oldest pending event"}
              label="Oldest pending"
              value={
                result.health.oldestPendingAgeSeconds === null
                  ? "None pending"
                  : `${Math.round(result.health.oldestPendingAgeSeconds)}s`
              }
            />
            <MetricCard
              detail={`In the last ${result.health.windowMinutes} minutes`}
              label="Recently dead-lettered"
              value={String(result.health.recentlyDeadLetteredCount)}
            />
            <MetricCard
              detail={`In the last ${result.health.windowMinutes} minutes`}
              label="Recently published"
              value={String(result.health.recentlyPublishedCount)}
            />
          </section>
        ) : null}
      </Panel>

      {refreshError ? (
        <SafeAlert title="Metrics may be stale" tone="attention">
          The most recent refresh failed. The health and event data shown below is from the last successful load
          {result ? ` (as of ${new Date(result.health.asOf).toLocaleString()})` : ""}. No sensitive diagnostic
          detail was exposed.
        </SafeAlert>
      ) : null}

      <Panel>
        <SectionHeader
          description="Searches use a same-origin POST body. No Replay, Requeue, Delete, Skip, or Force Publish control is exposed here."
          eyebrow="Authorized read surface"
          title="Search filters"
        />
        <form aria-busy={loading} aria-label="Filter outbox events" className="filterBar" onSubmit={submit}>
          <div className="filterFields">
            <FilterField label="Statuses" name="statuses">
              <select defaultValue={[]} multiple name="statuses" size={4}>
                {OutboxStatusValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Event type" name="eventType">
              <input maxLength={160} name="eventType" type="text" />
            </FilterField>
            <FilterField label="Occurred from" name="occurredFrom">
              <input name="occurredFrom" type="datetime-local" />
            </FilterField>
            <FilterField label="Occurred to" name="occurredTo">
              <input name="occurredTo" type="datetime-local" />
            </FilterField>
            <FilterField label="Minimum attempts" name="minAttemptCount">
              <input max={1000} min={0} name="minAttemptCount" type="number" />
            </FilterField>
            <FilterField label="Maximum attempts" name="maxAttemptCount">
              <input max={1000} min={0} name="maxAttemptCount" type="number" />
            </FilterField>
            <FilterField label="Page size" name="pageSize">
              <select defaultValue="25" name="pageSize">
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </FilterField>
          </div>
          <button className="button button--secondary" disabled={loading} type="submit">
            {loading ? "Searching…" : "Apply filters"}
          </button>
        </form>
      </Panel>

      <div aria-live="polite" aria-relevant="additions text">
        {loading && !result ? (
          <ApplicationState
            description="The privacy-minimized read model is being queried."
            kind="loading"
            title="Loading outbox delivery state"
          />
        ) : failure ? (
          <ApplicationState
            action={
              <button className="actionLink" onClick={() => void runSearch(filters)} type="button">
                Retry search
              </button>
            }
            description={failure.description}
            kind={failure.kind}
            title={failure.title}
          />
        ) : result && result.events.records.length === 0 ? (
          <ApplicationState
            action={
              <button className="actionLink" onClick={() => void runSearch(DEFAULT_FILTERS)} type="button">
                Clear filters
              </button>
            }
            description="The privacy-minimized read model returned no records for the selected filters."
            kind="empty"
            title="No outbox events match this search"
          />
        ) : result ? (
          <Panel>
            <SectionHeader
              description="Delivery is at-least-once: a duplicate publish after a reclaimed claim is possible and expected, not an error. Timestamps are shown in UTC."
              eyebrow="Delivery records"
              title={`${result.events.records.length} event${result.events.records.length === 1 ? "" : "s"}`}
              trailing={
                <StatusBadge
                  label={result.events.hasMore ? "more available" : "end of results"}
                  tone={result.events.hasMore ? "info" : "muted"}
                />
              }
            />

            <DataTable
              caption="Outbox delivery records"
              columns={[
                { key: "occurredAt", label: "Occurred at" },
                { key: "eventType", label: "Event type" },
                { key: "aggregateType", label: "Aggregate" },
                { key: "status", label: "Status" },
                { key: "correlation", label: "Correlation" },
                { key: "claimed", label: "Claimed" },
                { key: "nextAttempt", label: "Next attempt" },
                { key: "outcome", label: "Outcome" },
                { key: "reason", label: "Dead-letter reason" },
              ]}
              rows={rows}
            />

            <nav aria-label="Outbox result pagination" className="outboxPagination">
              <button
                className="paginationLink"
                disabled={loading || !filters.cursor}
                onClick={() => void runSearch({ ...filters, cursor: undefined })}
                type="button"
              >
                First page
              </button>
              <button
                className="paginationLink"
                disabled={loading || !result.events.hasMore || !result.events.nextCursor}
                onClick={() => void runSearch({ ...filters, cursor: result.events.nextCursor })}
                type="button"
              >
                Next page
              </button>
            </nav>
          </Panel>
        ) : null}
      </div>
    </>
  );
}
