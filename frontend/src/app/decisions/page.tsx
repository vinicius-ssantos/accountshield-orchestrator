import { readFrontendEnvironment } from "@/config/environment";
import {
  AppShell,
  PageHeader,
} from "@/design-system/components";
import { DecisionInvestigationConsole } from "@/features/decisions/decision-investigation-console";
import { SessionStatusBadge } from "@/features/session/session-status-badge";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export default function DecisionsPage() {
  const environment = readFrontendEnvironment(process.env, "runtime");
  const environmentLabel = environment.dataSource === "live" ? "Live data" : "Fixture mode";
  const environmentDetail =
    environment.dataSource === "live"
      ? "authorized read-only investigation"
      : "synthetic records · no backend calls";

  return (
    <AppShell
      activeHref="/decisions"
      environmentDetail={environmentDetail}
      environmentLabel={environmentLabel}
      sessionSlot={<SessionStatusBadge />}
    >
      <PageHeader
        eyebrow="Security operations"
        title="Decision investigation"
        description="Search the privacy-minimized decision read model without placing correlation IDs, cursors, or request filters in the URL."
      />
      <DecisionInvestigationConsole />
    </AppShell>
  );
}
