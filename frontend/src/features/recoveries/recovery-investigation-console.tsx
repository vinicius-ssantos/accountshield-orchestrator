"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";

import {
  ApplicationState,
  DataTable,
  FilterField,
  MaskedIdentifier,
  Panel,
  SectionHeader,
  StatusBadge,
  Timestamp,
  type DataTableRow,
  type StatusTone,
} from "@/design-system/components";

import { RecoveryDetailPanel } from "./recovery-detail-panel";
import {
  RecoverySearchBrowserError,
  searchRecoveriesThroughBff,
} from "./recovery-search-browser";
import {
  RecoveryClassificationValues,
  RecoveryEventTypeValues,
  RecoveryReviewStateValues,
  RecoveryStatusValues,
  type RecoveryClassification,
  type RecoveryEventType,
  type RecoveryReviewState,
  type RecoverySearchCriteria,
  type RecoverySearchPage,
  type RecoveryStatus,
} from "./types";

const DEFAULT_CRITERIA: RecoverySearchCriteria = { pageSize: 25 };

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

function optionalRiskScore(value: FormDataEntryValue | null): number | undefined {
  const normalized = optionalString(value);
  if (!normalized) return undefined;
  const parsed = Number.parseInt(normalized, 10);
  return Number.isInteger(parsed) ? parsed : undefined;
}

function criteriaFromForm(form: HTMLFormElement): RecoverySearchCriteria {
  const data = new FormData(form);
  const pageSize = Number.parseInt(String(data.get("pageSize") ?? "25"), 10);
  return {
    status: optionalString(data.get("status")) as RecoveryStatus | undefined,
    classification: optionalString(data.get("classification")) as RecoveryClassification | undefined,
    eventType: optionalString(data.get("eventType")) as RecoveryEventType | undefined,
    reviewState: optionalString(data.get("reviewState")) as RecoveryReviewState | undefined,
    initiatedFrom: toInstant(data.get("initiatedFrom")),
    initiatedTo: toInstant(data.get("initiatedTo")),
    minimumRiskScore: optionalRiskScore(data.get("minimumRiskScore")),
    maximumRiskScore: optionalRiskScore(data.get("maximumRiskScore")),
    pageSize: Number.isInteger(pageSize) ? pageSize : 25,
  };
}

function statusTone(status: string): StatusTone {
  if (status === "COMPLETED") return "positive";
  if (status === "REJECTED" || status === "IDENTITY_FAILED" || status === "ABORTED") return "critical";
  if (status === "DELAYED" || status === "MANUAL_REVIEW") return "attention";
  return "info";
}

function riskBandLabel(riskScore: number): string {
  if (riskScore >= 70) return "HIGH";
  if (riskScore >= 30) return "MEDIUM";
  return "LOW";
}

function riskTone(riskScore: number): StatusTone {
  if (riskScore >= 70) return "critical";
  if (riskScore >= 30) return "attention";
  return "positive";
}

function failureState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "unavailable" | "degraded";
  title: string;
  description: string;
} {
  if (error instanceof RecoverySearchBrowserError && error.status === 401) {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof RecoverySearchBrowserError && error.status === 403) {
    return {
      kind: "forbidden",
      title: "Recovery access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  if (error instanceof RecoverySearchBrowserError && error.status === 400) {
    return {
      kind: "degraded",
      title: "The filter combination is invalid",
      description: "Review the filter values and time range. No sensitive diagnostic detail was exposed.",
    };
  }
  return {
    kind: "unavailable",
    title: "Recovery search is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

export function RecoveryInvestigationConsole() {
  const [criteria, setCriteria] = useState<RecoverySearchCriteria>(DEFAULT_CRITERIA);
  const [result, setResult] = useState<RecoverySearchPage>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [selectedRecoveryReference, setSelectedRecoveryReference] = useState<string>();
  const requestSequence = useRef(0);

  const runSearch = useCallback(async (next: RecoverySearchCriteria) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(undefined);
    setSelectedRecoveryReference(undefined);
    try {
      const page = await searchRecoveriesThroughBff(next);
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

  const rows: readonly DataTableRow[] = (result?.recoveries ?? []).map((recovery) => ({
    id: recovery.recoveryReference,
    cells: {
      initiatedAt: <Timestamp value={recovery.initiatedAt} />,
      subject: (
        <MaskedIdentifier
          label="Masked subject reference"
          maskedValue={recovery.maskedSubjectReference}
        />
      ),
      event: recovery.eventType.replaceAll("_", " "),
      status: <StatusBadge label={recovery.status.replaceAll("_", " ")} tone={statusTone(recovery.status)} />,
      classification: (
        <span className="decisionFlags">
          <StatusBadge label={recovery.classification.replaceAll("_", " ")} tone="info" />
          {recovery.terminal ? <StatusBadge label="terminal" tone="muted" /> : null}
        </span>
      ),
      risk: (
        <span className="decisionRisk">
          <strong>{recovery.riskScore}</strong>
          <StatusBadge label={riskBandLabel(recovery.riskScore)} tone={riskTone(recovery.riskScore)} />
        </span>
      ),
      eligibility: recovery.eligibleAfter ? (
        <Timestamp label="Eligible after" value={recovery.eligibleAfter} />
      ) : (
        <span className="muted">Not delayed</span>
      ),
      review: <StatusBadge label={recovery.reviewState.replaceAll("_", " ")} tone={recovery.reviewState === "PENDING" ? "attention" : "muted"} />,
      decision: (
        <MaskedIdentifier
          label="Masked originating decision reference"
          maskedValue={recovery.originatingDecisionReference}
        />
      ),
      action: (
        <button
          aria-pressed={selectedRecoveryReference === recovery.recoveryReference}
          className="actionLink decisionInvestigateAction"
          onClick={() => setSelectedRecoveryReference(recovery.recoveryReference)}
          type="button"
        >
          Investigate recovery
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
          description="Searches use a same-origin POST body. Recovery references and filters are never written to the page URL."
        />
        <form
          aria-busy={loading}
          aria-label="Filter recovery investigations"
          className="filterBar"
          onSubmit={submit}
        >
          <div className="filterFields">
            <FilterField label="Status" name="status">
              <select defaultValue="" name="status">
                <option value="">All statuses</option>
                {RecoveryStatusValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Classification" name="classification">
              <select defaultValue="" name="classification">
                <option value="">All classifications</option>
                {RecoveryClassificationValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Event" name="eventType">
              <select defaultValue="" name="eventType">
                <option value="">All events</option>
                {RecoveryEventTypeValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Review state" name="reviewState">
              <select defaultValue="" name="reviewState">
                <option value="">All review states</option>
                {RecoveryReviewStateValues.map((value) => (
                  <option key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </FilterField>
            <FilterField label="Minimum risk" name="minimumRiskScore">
              <input max={100} min={0} name="minimumRiskScore" type="number" />
            </FilterField>
            <FilterField label="Maximum risk" name="maximumRiskScore">
              <input max={100} min={0} name="maximumRiskScore" type="number" />
            </FilterField>
            <FilterField label="Initiated from" name="initiatedFrom">
              <input name="initiatedFrom" type="datetime-local" />
            </FilterField>
            <FilterField label="Initiated to" name="initiatedTo">
              <input name="initiatedTo" type="datetime-local" />
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
            title="Loading recovery investigations"
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
        ) : result && result.recoveries.length === 0 ? (
          <ApplicationState
            kind="empty"
            title="No recoveries match this investigation"
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
              title={`${result.recoveries.length} recover${result.recoveries.length === 1 ? "y" : "ies"}`}
              description={`Source: ${result.source}. Subject and decision identifiers remain masked in the table. No approve, reject, retry, or complete control is exposed here.`}
              trailing={
                <StatusBadge
                  label={result.hasMore ? "more available" : "end of results"}
                  tone={result.hasMore ? "info" : "muted"}
                />
              }
            />

            <DataTable
              caption="Recovery investigation results"
              columns={[
                { key: "initiatedAt", label: "Initiated at" },
                { key: "subject", label: "Subject" },
                { key: "event", label: "Event" },
                { key: "status", label: "Status" },
                { key: "classification", label: "Classification" },
                { key: "risk", label: "Risk" },
                { key: "eligibility", label: "Eligibility" },
                { key: "review", label: "Review" },
                { key: "decision", label: "Originating decision" },
                { key: "action", label: "Investigation" },
              ]}
              rows={rows}
            />

            <nav aria-label="Recovery result pagination" className="decisionPagination">
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

      {selectedRecoveryReference ? (
        <RecoveryDetailPanel
          onClose={() => setSelectedRecoveryReference(undefined)}
          recoveryReference={selectedRecoveryReference}
        />
      ) : null}
    </>
  );
}
