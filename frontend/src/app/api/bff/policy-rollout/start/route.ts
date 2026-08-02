import { readFrontendEnvironment } from "@/config/environment";
import { fixturePolicyRolloutService } from "@/server/bff/policy-rollout-fixtures";
import { handleStartRolloutRequest } from "@/server/bff/policy-rollout";
import type { PolicyRolloutService } from "@/server/bff/policy-rollout-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): PolicyRolloutService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixturePolicyRolloutService : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleStartRolloutRequest(request, configuredService());
}
