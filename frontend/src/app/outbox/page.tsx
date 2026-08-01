import { readFrontendEnvironment } from "@/config/environment";
import { AppShell, PageHeader } from "@/design-system/components";
import { OutboxOperatorConsole } from "@/features/outbox/outbox-console";
import { SessionStatusBadge } from "@/features/session/session-status-badge";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export default function OutboxPage() {
  const environment = readFrontendEnvironment(process.env, "runtime");
  const environmentLabel = environment.dataSource === "live" ? "Live data" : "Fixture mode";
  const environmentDetail =
    environment.dataSource === "live"
      ? "authorized read-only investigation"
      : "synthetic records · no backend calls";

  return (
    <AppShell
      activeHref="/outbox"
      environmentDetail={environmentDetail}
      environmentLabel={environmentLabel}
      sessionSlot={<SessionStatusBadge />}
    >
      <PageHeader
        description="Read-only visibility into outbox delivery, retry, lag, and dead-letter state. No Replay, Requeue, Delete, Skip, or Force Publish control is exposed here."
        eyebrow="Security operations"
        title="Outbox delivery"
      />
      <OutboxOperatorConsole />
    </AppShell>
  );
}
