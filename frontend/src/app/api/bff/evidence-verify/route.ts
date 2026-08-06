import { readFrontendEnvironment } from "@/config/environment";
import { fixtureEvidenceVerifyService } from "@/server/bff/evidence-verify-fixtures";
import { handleEvidenceVerifyRequest } from "@/server/bff/evidence-verify";
import type { EvidenceVerifyService } from "@/server/bff/evidence-verify-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): EvidenceVerifyService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureEvidenceVerifyService : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleEvidenceVerifyRequest(request, configuredService());
}
