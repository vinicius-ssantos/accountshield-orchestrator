import { readFrontendEnvironment } from "@/config/environment";
import { fixtureRecoveryReviewService } from "@/server/bff/recovery-review-fixtures";
import { handleStepUpRequest } from "@/server/bff/recovery-review";
import type { RecoveryReviewService } from "@/server/bff/recovery-review-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): RecoveryReviewService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureRecoveryReviewService : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleStepUpRequest(request, configuredService());
}
