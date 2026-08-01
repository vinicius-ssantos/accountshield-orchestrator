import Link from "next/link";

import {
  AppShell,
  DataTable,
  MetricCard,
  PageHeader,
  Panel,
  SectionHeader,
  StatusBadge,
  type StatusTone,
} from "@/design-system/components";
import { getDecisionsDataSource } from "@/features/decisions/get-data-source";
import { SessionStatusBadge } from "@/features/session/session-status-badge";

function outcomeTone(outcome: string): StatusTone {
  const normalized = outcome.toLowerCase();
  if (normalized.includes("allow")) return "positive";
  if (normalized.includes("deny") || normalized.includes("block")) return "critical";
  if (normalized.includes("step") || normalized.includes("challenge")) return "attention";
  return "info";
}

function riskPresentation(score: number): { label: string; tone: StatusTone } {
  if (score >= 75) return { label: `High risk · ${score}`, tone: "critical" };
  if (score >= 40) return { label: `Medium risk · ${score}`, tone: "attention" };
  return { label: `Low risk · ${score}`, tone: "positive" };
}

export default async function Home() {
  const decisionsDataSource = getDecisionsDataSource();
  const [metrics, decisions] = await Promise.all([
    decisionsDataSource.listOverviewMetrics(),
    decisionsDataSource.listRecent(),
  ]);

  return (
    <AppShell activeHref="/" sessionSlot={<SessionStatusBadge />}>
      <PageHeader
        action={
          <Link className="actionLink" href="/decisions">
            Search correlation ID
          </Link>
        }
        description="Investigate decisions, explain risk, and prepare safe operator workflows."
        eyebrow="Operations overview"
        title="Account protection at a glance"
      />

      <section aria-label="Operations metrics" className="metricGrid">
        {metrics.map((metric) => (
          <MetricCard
            detail={metric.detail}
            key={metric.label}
            label={metric.label}
            value={metric.value}
          />
        ))}
      </section>

      <Panel>
        <SectionHeader
          eyebrow="Recent decisions"
          title="Investigation queue"
          trailing={<StatusBadge label="Read only" tone="attention" />}
        />

        <DataTable
          caption="Recent account-protection decisions"
          columns={[
            { key: "correlation", label: "Correlation" },
            { key: "event", label: "Event" },
            { key: "risk", label: "Risk" },
            { key: "outcome", label: "Outcome" },
            { key: "policy", label: "Policy" },
          ]}
          rows={decisions.map((decision) => {
            const risk = riskPresentation(decision.riskScore);
            return {
              id: decision.correlationId,
              cells: {
                correlation: <code>{decision.correlationId}</code>,
                event: decision.eventType,
                risk: <StatusBadge label={risk.label} tone={risk.tone} />,
                outcome: (
                  <StatusBadge
                    label={decision.outcome}
                    tone={outcomeTone(decision.outcome)}
                  />
                ),
                policy: decision.policyVersion,
              },
            };
          })}
        />
      </Panel>
    </AppShell>
  );
}
