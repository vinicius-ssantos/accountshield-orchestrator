import { readFrontendEnvironment } from "@/config/environment";
import {
  AppShell,
  PageHeader,
} from "@/design-system/components";
import { SessionStatusBadge } from "@/features/session/session-status-badge";

import { PolicyDirectoryConsoleWithSession } from "./policy-directory-console-with-session";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export default function PoliciesPage() {
  const environment = readFrontendEnvironment(process.env, "runtime");
  const environmentLabel = environment.dataSource === "live" ? "Live data" : "Fixture mode";
  const environmentDetail =
    environment.dataSource === "live"
      ? "authorized read-only investigation"
      : "synthetic records · no backend calls";

  return (
    <AppShell
      activeHref="/policies"
      environmentDetail={environmentDetail}
      environmentLabel={environmentLabel}
      sessionSlot={<SessionStatusBadge />}
    >
      <PageHeader
        eyebrow="Security operations"
        title="Policy lifecycle"
        description="Approve, activate, reject, and retire policy versions with fresh step-up. Rollout, rollback, and authoring remain out of scope."
      />
      <PolicyDirectoryConsoleWithSession />
    </AppShell>
  );
}
