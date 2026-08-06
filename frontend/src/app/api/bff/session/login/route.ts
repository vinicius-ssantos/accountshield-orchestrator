import { readFrontendEnvironment } from "@/config/environment";
import { fixtureSessionTokenService } from "@/server/bff/session/session-fixtures";
import { handleSessionLoginRequest, type SessionTokenService } from "@/server/bff/session/session";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function configuredService(): SessionTokenService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureSessionTokenService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleSessionLoginRequest(request, configuredService());
}
