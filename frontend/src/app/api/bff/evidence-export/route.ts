import { readFrontendEnvironment } from "@/config/environment";
import { fixtureEvidenceExportService } from "@/server/bff/evidence-export-fixtures";
import { handleEvidenceExportRequest } from "@/server/bff/evidence-export";
import type { EvidenceExportService } from "@/server/bff/evidence-export-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): EvidenceExportService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureEvidenceExportService : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleEvidenceExportRequest(request, configuredService());
}
