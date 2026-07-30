"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";

import {
  ApplicationState,
  DataTable,
  FilterField,
  MaskedIdentifier,
  Panel,
  SafeAlert,
  SectionHeader,
  StatusBadge,
  Timestamp,
  maskIdentifier,
  type DataTableRow,
  type StatusTone,
} from "@/design-system/components";

import {
  DecisionSearchBrowserError,
  searchDecisionsThroughBff,
} from "./decision-search-browser";
import { DecisionTimelinePanel } from "./decision-timeline-panel";
import {
  DecisionEventTypeValues,
  DecisionOutcomeValues,
  DecisionRiskBandValues,
  type DecisionEventType,
  type DecisionOutcome,
  type DecisionRiskBand,
  type DecisionSearchCriteria,
  type DecisionSearchPage,
} from "./types";

const DEFAULT_CRITERIA: DecisionSearchCriteria = { pageSize: 25 };

function toInstant(value: FormDataEntryValue | null): string | undefined {
  if (typeof value !== "string" || !value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? undefined : parsed.toISOString();
}

function optionalString(value: FormDataEntryValue | null): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim();
  return normalized || undefined;
}

function criteriaFromForm(form: HTMLFormElement): DecisionSearchCriteria {
  const data = new FormData(form);
  const pageSize = Number.parseInt(String(data.get("pageSize") ?? "25"), 10);
  return {
    correlationId: optionalString(data.get("correlationId")),
    eventType: optionalString(data.get("eventType")) as DecisionEventType | undefined,
    outcome: optionalString(data.get("outcome")) as DecisionOutcome | undefined,
    riskBand: optionalString(data.get("riskBand")) as DecisionRiskBand | undefined,
    policyVersion: optionalString(data.get("policyVersion")),
    decidedFrom: toInstant(data.get("from")),
    decidedTo: toInstant(data.get("to")),
    pageSize: Number.isInteger(pageSize) ? pageSize : 25,
  };
}

function outcomeTone(outcome: string): StatusTone {
  if (outcome === "ALLOW") return "positive";
  if (outcome === "REQUIRE_STEP_UP") return "attention";
  if (outcome === "START_RECOVERY" || outcome === "TEMPORARILY_BLOCK") return "critical";
  return "neutral";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "unavailable" | "degraded";
  title: string;
  description: string;
} {
  if (error instanceof DecisionSearchBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof DecisionSearchBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Decision access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof DecisionSearchBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "The filter combination is invalid",
      description: "Review the filter values and time range. No sensitive diagnostic detail was exposed.",
    };
  }
  return {
    kind: "unavailable",
    title: "Decision search is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

export function DecisionInvestigationConsole() {
  const [criteria, setCriteria] = useState<DecisionSearchCriteria>(DEFAULT_CRITERIA);
  const [result, setResult] = useState<DecisionSearchPage>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [selectedDecisionReference, setSelectedDecisionReference] = useState<string>();
  const requestSequence = useRef(0);

  const runSearch = useCallback(async (next: DecisionSearchCriteria) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    setSelectedDecisionReference(undefined);
    try {
      const page = await searchDecisionsThroughBff(next);
      if (sequence === requestSequence.current) {
        setCriteria(next);
        setResult(page);
      }
    } catch (searchError) {
      if (sequence === requestSequence.current) {
        setError(searchError);
        setResult(undefined);
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => void runSearch(DEFAULT_CRITERIA));
  }, [runSearch]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void runSearch(criteriaFromForm(event.currentTarget));
  }

  const rows: readonly DataTableRow[] = (result?.decisions ?? []).map((decision) => ({
    id: decision.decisionReference,
    cells: {
      decidedAt: <Timestamp value={decision.decidedAt} />,
      correlation: (
        <MaskedIdentifier
          label="Masked correlation ID"
          maskedValue={maskIdentifier(decision.correlationId, 6, 4)}
        />
      ),
      event: decision.eventType.replaceAll("_", " "),
      risk: (
        <span className="decisionRisk">
          <strong>{decision.riskScore}</strong>
          <StatusBadge
            label={decision.riskBand}
            tone={
              decision.riskBand === "HIGH"
                ? "critical"
                : decision.riskBand === "MEDIUM"
                  ? "attention"
                  : "positive"
            }
          />
        </span>
      ),
      outcome: <StatusBadge label={decision.outcome} tone={outcomeTone(decision.outcome)} />,
      policy: `${decision.policyKey} · ${decision.policyVersion}`,
      evidence: (
        <span className="decisionFlags">
          {decision.degraded ? <StatusBadge label="degraded" tone="attention" /> : null}
          {decision.simulated ? <StatusBadge label="simulated" tone="info" /> : null}
          {!decision.provenanceAvailable ? (
            <StatusBadge label="provenance gap" tone="critical" />
          ) : null}
          {!decision.degraded && !decision.simulated && decision.provenanceAvailable ? (
            <StatusBadge label="complete" tone="positive" />
          ) : null}
        </span>
      ),
      action: (
        <button
          aria-pressed={selectedDecisionReference === decision.decisionReference}
          className="actionLink decisionInvestigateAction"
          onClick={() => setSelectedDecisionReference(decision.decisionReference)}
          type="button"
        >
          Investigate decision
        </button>
      ),
    },
  }));

  const failure = error ? failureState(error) : undefined;

  return (
    <>
      <Panel>
        <SectionHeader
          eyebrow="Authorized read surface"
          title="Search filters"
          description="Searches use a same-origin POST body. Correlation IDs, cursors, and filters are never written to the page URL."
        />
        <form
          aria-busy={loading}
          aria-label="Filter decision investigations"
          className="filterBar"
          onSubmit={submit}
        >
          <div className="filterFields">
            <FilterField label="Correlation ID" name="correlationId">
              <input
                autoComplete="off"
                maxLength={128}
                name="correlationId"
                pattern="[A-Za-z0-9._-]{1,128}"
                placeholder="Exact correlation ID"
                type="search"
              />
            </FilterField>
            <FilterField label="Event" name="eventType">
              <select defaultValue="" name="eventType">
                <option value="">All events</option>
                {DecisionEventTypeValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Outcome" name="outcome">
              <select defaultValue="" name="outcome">
                <option value="">All outcomes</option>
                {DecisionOutcomeValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Risk band" name="riskBand">
              <select defaultValue="" name="riskBand">
                <option value="">All risk bands</option>
                {DecisionRiskBandValues.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Policy version" name="policyVersion">
              <input maxLength={40} name="policyVersion" placeholder="v7" />
            </FilterField>
            <FilterField label="Decided from" name="from">
              <input name="from" type="datetime-local" />
            </FilterField>
            <FilterField label="Decided to" name="to">
              <input name="to" type="datetime-local" />
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
            kind="loading"
            title="Loading decision investigations"
            description="The privacy-minimized read model is being queried."
          />
        ) : failure ? (
          <ApplicationState
            kind={failure.kind}
            title={failure.title}
            description={failure.description}
            action={
              <button
                className="actionLink"
                onClick={() => void runSearch(criteria)}
                type="button"
              >
                Retry search
              </button>
            }
          />
        ) : result && result.decisions.length === 0 ? (
          <ApplicationState
            kind="empty"
            title="No decisions match this investigation"
            description="The privacy-minimized read model returned no records for the selected filters."
            action={
              <button
                className="actionLink"
                onClick={() => void runSearch(DEFAULT_CRITERIA)}
                type="button"
              >
                Clear filters
              </button>
            }
          />
        ) : result ? (
          <Panel>
            <SectionHeader
              eyebrow="Investigation queue"
              title={`${result.decisions.length} decision${result.decisions.length === 1 ? "" : "s"}`}
              description={`Source: ${result.source}. Correlation and decision identifiers remain masked in the table.`}
              trailing={
                <StatusBadge
                  label={result.hasMore ? "more available" : "end of results"}
                  tone={result.hasMore ? "info" : "muted"}
                />
              }
            />

            {result.partial ? (
              <SafeAlert title="Partial result" tone="attention">
                Some records could not be loaded. The rows below remain safe to investigate.
              </SafeAlert>
            ) : null}

            <DataTable
              caption="Decision investigation results"
              columns={[
                { key: "decidedAt", label: "Decided at" },
                { key: "correlation", label: "Correlation" },
                { key: "event", label: "Event" },
                { key: "risk", label: "Risk" },
                { key: "outcome", label: "Outcome" },
                { key: "policy", label: "Policy" },
                { key: "evidence", label: "Evidence" },
                { key: "action", label: "Investigation" },
              ]}
              rows={rows}
            />

            <nav aria-label="Decision result pagination" className="decisionPagination">
              <button
                className="paginationLink"
                disabled={loading || !criteria.cursor}
                onClick={() => void runSearch({ ...criteria, cursor: undefined })}
                type="button"
              >
                First page
              </button>
              <button
                className="paginationLink"
                disabled={loading || !result.hasMore || !result.nextCursor}
                onClick={() =>
                  void runSearch({ ...criteria, cursor: result.nextCursor })
                }
                type="button"
              >
                Next page
              </button>
            </nav>
          </Panel>
        ) : null}
      </div>

      {selectedDecisionReference ? (
        <DecisionTimelinePanel
          decisionReference={selectedDecisionReference}
          onClose={() => setSelectedDecisionReference(undefined)}
        />
      ) : null}
    </>
  );
}
