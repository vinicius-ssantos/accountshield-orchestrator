import { readFrontendEnvironment } from "@/config/environment";
import { fixtureOutboxRequeueService } from "@/server/bff/outbox-requeue-fixtures";
import { handleRequeueRequest } from "@/server/bff/outbox-requeue";
import type { OutboxRequeueService } from "@/server/bff/outbox-requeue-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): OutboxRequeueService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureOutboxRequeueService : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleRequeueRequest(request, configuredService());
}
