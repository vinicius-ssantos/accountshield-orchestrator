import { readFrontendEnvironment } from "@/config/environment";
import {
  AppShell,
  PageHeader,
} from "@/design-system/components";
import { PolicyDirectoryConsole } from "@/features/policies/policy-directory-console";

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
    >
      <PageHeader
        eyebrow="Security operations"
        title="Policy lifecycle"
        description="Read-only visibility into policy versions, approval state, rollout status, and historical candidate impact. No authoring, approval, activation, or rollout control is exposed here."
      />
      <PolicyDirectoryConsole />
    </AppShell>
  );
}
