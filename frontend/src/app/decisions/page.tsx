import Link from "next/link";

import { readFrontendEnvironment } from "@/config/environment";
import {
  AppShell,
  ApplicationState,
  DataTable,
  FilterBar,
  FilterField,
  MaskedIdentifier,
  PageHeader,
  Panel,
  SafeAlert,
  SectionHeader,
  StatusBadge,
  Timestamp,
  maskIdentifier,
  type DataTableRow,
  type StatusTone,
} from "@/design-system/components";
import { getDecisionsDataSource } from "@/features/decisions/get-data-source";
import {
  DecisionEventTypeValues,
  DecisionOutcomeValues,
  DecisionRiskBandValues,
  type DecisionEventType,
  type DecisionOutcome,
  type DecisionRiskBand,
  type DecisionSearchCriteria,
} from "@/features/decisions/types";
import { BffError } from "@/server/bff/foundation";
import { searchLiveDecisions } from "@/server/bff/decision-search";

export const dynamic = "force-dynamic";
export const revalidate = 0;

interface SearchValues {
  correlationId: string;
  eventType: string;
  outcome: string;
  riskBand: string;
  policyVersion: string;
  from: string;
  to: string;
  cursor: string;
  pageSize: string;
}

type QueryValue = string | string[] | undefined;

function first(value: QueryValue): string {
  return Array.isArray(value) ? (value[0] ?? "") : (value ?? "");
}

function isOneOf<T extends string>(value: string, values: readonly T[]): value is T {
  return values.includes(value as T);
}

function toInstant(value: string): string | undefined {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? undefined : parsed.toISOString();
}

function parseSearchValues(searchParams: Record<string, QueryValue>): {
  values: SearchValues;
  criteria: DecisionSearchCriteria;
  invalid: boolean;
} {
  const values: SearchValues = {
    correlationId: first(searchParams.correlationId).trim(),
    eventType: first(searchParams.eventType),
    outcome: first(searchParams.outcome),
    riskBand: first(searchParams.riskBand),
    policyVersion: first(searchParams.policyVersion).trim(),
    from: first(searchParams.from),
    to: first(searchParams.to),
    cursor: first(searchParams.cursor),
    pageSize: first(searchParams.pageSize) || "25",
  };

  const eventType = isOneOf(values.eventType, DecisionEventTypeValues)
    ? (values.eventType as DecisionEventType)
    : undefined;
  const outcome = isOneOf(values.outcome, DecisionOutcomeValues)
    ? (values.outcome as DecisionOutcome)
    : undefined;
  const riskBand = isOneOf(values.riskBand, DecisionRiskBandValues)
    ? (values.riskBand as DecisionRiskBand)
    : undefined;
  const pageSize = Number.parseInt(values.pageSize, 10);
  const decidedFrom = toInstant(values.from);
  const decidedTo = toInstant(values.to);
  const invalid =
    Boolean(values.eventType && !eventType) ||
    Boolean(values.outcome && !outcome) ||
    Boolean(values.riskBand && !riskBand) ||
    !Number.isInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > 100 ||
    Boolean(values.from && !decidedFrom) ||
    Boolean(values.to && !decidedTo) ||
    Boolean(decidedFrom && decidedTo && decidedFrom > decidedTo);

  return {
    values,
    invalid,
    criteria: {
      correlationId: values.correlationId || undefined,
      eventType,
      outcome,
      riskBand,
      policyVersion: values.policyVersion || undefined,
      decidedFrom,
      decidedTo,
      cursor: values.cursor || undefined,
      pageSize: Number.isInteger(pageSize) && pageSize >= 1 && pageSize <= 100 ? pageSize : 25,
    },
  };
}

function outcomeTone(outcome: string): StatusTone {
  if (outcome === "ALLOW") return "positive";
  if (outcome === "REQUIRE_STEP_UP") return "attention";
  if (outcome === "START_RECOVERY" || outcome === "TEMPORARILY_BLOCK") return "critical";
  return "neutral";
}

function buildHref(values: SearchValues, cursor: string | undefined): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (key !== "cursor" && value) params.set(key, value);
  }
  if (cursor) params.set("cursor", cursor);
  const query = params.toString();
  return query ? `/decisions?${query}` : "/decisions";
}

function errorState(error: unknown): {
  kind: "unauthorized" | "forbidden" | "unavailable";
  title: string;
  description: string;
} {
  if (error instanceof BffError && error.code === "UNAUTHORIZED") {
    return {
      kind: "unauthorized",
      title: "Operator authentication is required",
      description: "The server-side operator credential is missing or no longer valid.",
    };
  }
  if (error instanceof BffError && error.code === "FORBIDDEN") {
    return {
      kind: "forbidden",
      title: "Decision access is not permitted",
      description: "The authenticated principal does not have the SECURITY_OPERATOR role.",
    };
  }
  return {
    kind: "unavailable",
    title: "Decision search is temporarily unavailable",
    description: "No sensitive diagnostic detail was exposed. Retry after the backend is healthy.",
  };
}

export default async function DecisionsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, QueryValue>>;
}) {
  const parsed = parseSearchValues(await searchParams);
  const environment = readFrontendEnvironment(process.env, "runtime");
  const environmentLabel = environment.dataSource === "live" ? "Live data" : "Fixture mode";
  const environmentDetail =
    environment.dataSource === "live"
      ? "authorized read-only investigation"
      : "synthetic records · no backend calls";

  let result;
  let failure: ReturnType<typeof errorState> | undefined;

  if (!parsed.invalid) {
    try {
      result =
        environment.dataSource === "fixtures"
          ? await getDecisionsDataSource().search(parsed.criteria)
          : await searchLiveDecisions(parsed.criteria);
    } catch (error) {
      failure = errorState(error);
    }
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
            tone={decision.riskBand === "HIGH" ? "critical" : decision.riskBand === "MEDIUM" ? "attention" : "positive"}
          />
        </span>
      ),
      outcome: <StatusBadge label={decision.outcome} tone={outcomeTone(decision.outcome)} />,
      policy: `${decision.policyKey} · ${decision.policyVersion}`,
      evidence: (
        <span className="decisionFlags">
          {decision.degraded ? <StatusBadge label="degraded" tone="attention" /> : null}
          {decision.simulated ? <StatusBadge label="simulated" tone="info" /> : null}
          {!decision.provenanceAvailable ? <StatusBadge label="provenance gap" tone="critical" /> : null}
          {!decision.degraded && !decision.simulated && decision.provenanceAvailable ? (
            <StatusBadge label="complete" tone="positive" />
          ) : null}
        </span>
      ),
    },
  }));

  return (
    <AppShell
      activeHref="/decisions"
      environmentDetail={environmentDetail}
      environmentLabel={environmentLabel}
    >
      <PageHeader
        eyebrow="Security operations"
        title="Decision investigation"
        description="Search the privacy-minimized decision read model without exposing account references, raw signals, or challenge material."
        action={
          <Link className="button button--secondary" href="/decisions">
            Clear filters
          </Link>
        }
      />

      <Panel>
        <SectionHeader
          eyebrow="Authorized read surface"
          title="Search filters"
          description="Filters are stored in the URL so an investigation view can be shared without placing the request body or secrets in telemetry."
        />
        <FilterBar action="/decisions" ariaLabel="Filter decision investigations">
          <FilterField label="Correlation ID" name="correlationId">
            <input
              autoComplete="off"
              defaultValue={parsed.values.correlationId}
              maxLength={128}
              name="correlationId"
              pattern="[A-Za-z0-9._-]{1,128}"
              placeholder="Exact correlation ID"
              type="search"
            />
          </FilterField>
          <FilterField label="Event" name="eventType">
            <select defaultValue={parsed.values.eventType} name="eventType">
              <option value="">All events</option>
              {DecisionEventTypeValues.map((value) => (
                <option key={value} value={value}>{value.replaceAll("_", " ")}</option>
              ))}
            </select>
          </FilterField>
          <FilterField label="Outcome" name="outcome">
            <select defaultValue={parsed.values.outcome} name="outcome">
              <option value="">All outcomes</option>
              {DecisionOutcomeValues.map((value) => (
                <option key={value} value={value}>{value.replaceAll("_", " ")}</option>
              ))}
            </select>
          </FilterField>
          <FilterField label="Risk band" name="riskBand">
            <select defaultValue={parsed.values.riskBand} name="riskBand">
              <option value="">All risk bands</option>
              {DecisionRiskBandValues.map((value) => (
                <option key={value} value={value}>{value}</option>
              ))}
            </select>
          </FilterField>
          <FilterField label="Policy version" name="policyVersion">
            <input defaultValue={parsed.values.policyVersion} maxLength={40} name="policyVersion" placeholder="v7" />
          </FilterField>
          <FilterField label="Decided from" name="from">
            <input defaultValue={parsed.values.from} name="from" type="datetime-local" />
          </FilterField>
          <FilterField label="Decided to" name="to">
            <input defaultValue={parsed.values.to} name="to" type="datetime-local" />
          </FilterField>
          <FilterField label="Page size" name="pageSize">
            <select defaultValue={parsed.values.pageSize} name="pageSize">
              <option value="10">10</option>
              <option value="25">25</option>
              <option value="50">50</option>
              <option value="100">100</option>
            </select>
          </FilterField>
        </FilterBar>
      </Panel>

      {parsed.invalid ? (
        <ApplicationState
          kind="degraded"
          title="The filter combination is invalid"
          description="Review the enum values, page size, and time range. No backend request was sent."
          action={<Link className="actionLink" href="/decisions">Reset search</Link>}
        />
      ) : failure ? (
        <ApplicationState
          kind={failure.kind}
          title={failure.title}
          description={failure.description}
          action={<Link className="actionLink" href={buildHref(parsed.values, undefined)}>Retry search</Link>}
        />
      ) : result && result.decisions.length === 0 ? (
        <ApplicationState
          kind="empty"
          title="No decisions match this investigation"
          description="The privacy-minimized read model returned no records for the selected filters."
          action={<Link className="actionLink" href="/decisions">Clear filters</Link>}
        />
      ) : result ? (
        <Panel>
          <SectionHeader
            eyebrow="Investigation queue"
            title={`${result.decisions.length} decision${result.decisions.length === 1 ? "" : "s"}`}
            description={`Source: ${result.source}. Correlation and decision identifiers remain masked in the table.`}
            trailing={<StatusBadge label={result.hasMore ? "more available" : "end of results"} tone={result.hasMore ? "info" : "muted"} />}
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
            ]}
            rows={rows}
          />

          <nav aria-label="Decision result pagination" className="decisionPagination">
            <Link className="paginationLink" href={buildHref(parsed.values, undefined)}>
              First page
            </Link>
            {result.hasMore && result.nextCursor ? (
              <Link className="paginationLink" href={buildHref(parsed.values, result.nextCursor)}>
                Next page
              </Link>
            ) : (
              <span aria-disabled="true" className="paginationLink isDisabled">Next page</span>
            )}
          </nav>
        </Panel>
      ) : null}
    </AppShell>
  );
}
