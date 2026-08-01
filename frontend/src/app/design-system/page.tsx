import Link from "next/link";

import {
  AppShell,
  ApplicationState,
  DataTable,
  FilterBar,
  FilterField,
  MaskedIdentifier,
  PageHeader,
  Pagination,
  Panel,
  SafeAlert,
  SectionHeader,
  StatusBadge,
  Timeline,
  Timestamp,
  maskIdentifier,
  type NavigationItem,
} from "@/design-system/components";
import { SessionStatusBadge } from "@/features/session/session-status-badge";

const SHOWCASE_NAVIGATION: readonly NavigationItem[] = [
  { label: "Overview", href: "/" },
  { label: "Decisions", href: "/decisions" },
  { label: "Recoveries", href: "/recoveries" },
  { label: "Policies", href: "/policies" },
  { label: "Replay", href: "/replay" },
  { label: "Operations", href: "/operations" },
  { label: "Design system", href: "/design-system" },
];

const RAW_SHOWCASE_IDENTIFIER = "acct_72c4b69e18f74291";
const MASKED_SHOWCASE_IDENTIFIER = maskIdentifier(
  RAW_SHOWCASE_IDENTIFIER,
  7,
  4,
);

export default function DesignSystemPage() {
  return (
    <AppShell
      activeHref="/design-system"
      environmentDetail="synthetic examples only"
      environmentLabel="Showcase mode"
      navigationItems={SHOWCASE_NAVIGATION}
      sessionSlot={<SessionStatusBadge />}
    >
      <PageHeader
        action={
          <Link className="actionLink" href="/">
            Return to overview
          </Link>
        }
        description="Shared, accessible patterns for the read-only operator workflows in issues #69–#74. All examples use synthetic data."
        eyebrow="Internal reference"
        title="AccountShield console design system"
      />

      <div className="showcaseGrid">
        <Panel className="showcaseFullWidth">
          <SectionHeader
            description="Every status combines stable text, a symbol, and semantic styling. Color is never the only signal."
            eyebrow="Semantic tokens"
            title="Operational status language"
          />
          <div className="statusCollection">
            <StatusBadge label="Fixture data" tone="attention" />
            <StatusBadge label="Simulated" tone="info" />
            <StatusBadge label="Live" tone="positive" />
            <StatusBadge label="Stale" tone="muted" />
            <StatusBadge label="Degraded" tone="attention" />
            <StatusBadge label="Unavailable" tone="critical" />
            <StatusBadge label="Read only" tone="neutral" />
          </div>
        </Panel>

        <Panel>
          <SectionHeader
            description="Only the already-redacted representation reaches rendered output."
            eyebrow="Sensitive data"
            title="Masked identifiers"
          />
          <div className="showcaseStack">
            <MaskedIdentifier
              label="Synthetic account identifier"
              maskedValue={MASKED_SHOWCASE_IDENTIFIER}
            />
            <Timestamp
              label="Example investigation timestamp"
              timeZone="UTC"
              value="2026-07-25T18:30:00.000Z"
            />
            <SafeAlert title="Masking contract" tone="info">
              <p>
                The raw value is reduced in the Server Component and is not
                placed in attributes, tooltips, labels, or client state.
              </p>
            </SafeAlert>
          </div>
        </Panel>

        <Panel>
          <SectionHeader
            description="Native controls and URL-driven filters remain usable without client JavaScript."
            eyebrow="Progressive enhancement"
            title="Filters and pagination"
          />
          <FilterBar action="/design-system">
            <FilterField label="Outcome" name="outcome">
              <select defaultValue="all" name="outcome">
                <option value="all">All outcomes</option>
                <option value="allowed">Allowed</option>
                <option value="step-up">Step up required</option>
                <option value="denied">Denied</option>
              </select>
            </FilterField>
            <FilterField label="Correlation ID" name="correlationId">
              <input
                autoComplete="off"
                name="correlationId"
                placeholder="corr_demo"
                type="search"
              />
            </FilterField>
          </FilterBar>
          <Pagination basePath="/design-system" currentPage={2} pageCount={4} />
        </Panel>

        <Panel className="showcaseFullWidth">
          <SectionHeader
            description="Tables keep captions, scoped headers, textual statuses, and horizontally safe overflow."
            eyebrow="Investigation data"
            title="Table pattern"
            trailing={<StatusBadge label="Synthetic data" tone="info" />}
          />
          <DataTable
            caption="Synthetic design-system decision examples"
            columns={[
              { key: "account", label: "Account" },
              { key: "event", label: "Event" },
              { key: "risk", label: "Risk" },
              { key: "outcome", label: "Outcome" },
              { key: "observed", label: "Observed" },
            ]}
            rows={[
              {
                id: "showcase-row-1",
                cells: {
                  account: (
                    <MaskedIdentifier
                      label="Synthetic account identifier"
                      maskedValue={MASKED_SHOWCASE_IDENTIFIER}
                    />
                  ),
                  event: "PASSWORD_LOGIN",
                  risk: <StatusBadge label="High risk · 87" tone="critical" />,
                  outcome: (
                    <StatusBadge label="Step up required" tone="attention" />
                  ),
                  observed: (
                    <Timestamp
                      timeZone="UTC"
                      value="2026-07-25T18:30:00.000Z"
                    />
                  ),
                },
              },
              {
                id: "showcase-row-2",
                cells: {
                  account: (
                    <MaskedIdentifier
                      label="Synthetic account identifier"
                      maskedValue="acct_91••••8042"
                    />
                  ),
                  event: "PASSKEY_LOGIN",
                  risk: <StatusBadge label="Low risk · 12" tone="positive" />,
                  outcome: <StatusBadge label="Allowed" tone="positive" />,
                  observed: (
                    <Timestamp
                      timeZone="UTC"
                      value="2026-07-25T18:26:00.000Z"
                    />
                  ),
                },
              },
            ]}
          />
        </Panel>

        <Panel>
          <SectionHeader
            description="Lifecycle history is an ordered list with explicit timestamps and status labels."
            eyebrow="Audit narrative"
            title="Timeline pattern"
          />
          <Timeline
            items={[
              {
                id: "received",
                title: "Signal received",
                description: "Synthetic login telemetry entered the decision pipeline.",
                timestamp: "2026-07-25T18:29:58.000Z",
                status: "Recorded",
                tone: "info",
              },
              {
                id: "evaluated",
                title: "Policy evaluated",
                description: "Policy version demo-7 produced a high-risk classification.",
                timestamp: "2026-07-25T18:29:59.000Z",
                status: "High risk",
                tone: "critical",
              },
              {
                id: "challenged",
                title: "Challenge required",
                description: "The read-only console reports the required next control without executing it.",
                timestamp: "2026-07-25T18:30:00.000Z",
                status: "Step up",
                tone: "attention",
              },
            ]}
          />
        </Panel>

        <Panel>
          <SectionHeader
            description="Application states communicate provenance and operator guidance without overstating certainty."
            eyebrow="System feedback"
            title="Loading and failure states"
          />
          <div className="stateCollection">
            <ApplicationState
              description="The investigation query is still running. No result has been inferred."
              kind="loading"
              title="Loading decisions"
            />
            <ApplicationState
              description="No synthetic decisions match the current filter set."
              kind="empty"
              title="No matching decisions"
            />
            <ApplicationState
              description="Cached data is visible, but freshness is outside the expected operating window."
              kind="degraded"
              title="Data may be stale"
            />
            <ApplicationState
              action={
                <Link className="actionLink" href="/">
                  Return to overview
                </Link>
              }
              description="The operator does not have permission to view this workflow. Resource existence is not disclosed."
              kind="forbidden"
              title="Access not available"
            />
          </div>
        </Panel>

        <Panel className="showcaseFullWidth">
          <SectionHeader
            description="Alerts describe the operational consequence instead of relying on urgency styling alone."
            eyebrow="Safe communication"
            title="Alert patterns"
          />
          <div className="showcaseStack">
            <SafeAlert title="Read-only contract" tone="positive">
              <p>
                This showcase and the current operator workflows do not call
                administrative mutation endpoints.
              </p>
            </SafeAlert>
            <SafeAlert title="Degraded dependency" tone="attention">
              <p>
                Label cached or partial data explicitly and preserve the last
                verified timestamp.
              </p>
            </SafeAlert>
            <SafeAlert title="Unavailable upstream" tone="critical">
              <p>
                Do not reveal internal hostnames, credentials, or unrestricted
                upstream error payloads to the operator.
              </p>
            </SafeAlert>
          </div>
        </Panel>
      </div>
    </AppShell>
  );
}
