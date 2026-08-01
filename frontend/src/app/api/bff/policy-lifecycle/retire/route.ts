import { readFrontendEnvironment } from "@/config/environment";
import { fixturePolicyLifecycleService } from "@/server/bff/policy-lifecycle-fixtures";
import { handleRetireRequest } from "@/server/bff/policy-lifecycle";
import type { PolicyLifecycleService } from "@/server/bff/policy-lifecycle-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): PolicyLifecycleService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixturePolicyLifecycleService : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleRetireRequest(request, configuredService());
}
