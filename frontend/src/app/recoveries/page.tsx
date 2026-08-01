import { readFrontendEnvironment } from "@/config/environment";
import {
  AppShell,
  PageHeader,
} from "@/design-system/components";
import { RecoveryInvestigationConsole } from "@/features/recoveries/recovery-investigation-console";
import { SessionStatusBadge } from "@/features/session/session-status-badge";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export default function RecoveriesPage() {
  const environment = readFrontendEnvironment(process.env, "runtime");
  const environmentLabel = environment.dataSource === "live" ? "Live data" : "Fixture mode";
  const environmentDetail =
    environment.dataSource === "live"
      ? "authorized read-only investigation"
      : "synthetic records · no backend calls";

  return (
    <AppShell
      activeHref="/recoveries"
      environmentDetail={environmentDetail}
      environmentLabel={environmentLabel}
      sessionSlot={<SessionStatusBadge />}
    >
      <PageHeader
        eyebrow="Security operations"
        title="Recovery investigation"
        description="Locate and triage recovery flows without placing subject or recovery references in the URL. No Approve, Reject, Retry, or Complete control is exposed here."
      />
      <RecoveryInvestigationConsole />
    </AppShell>
  );
}
