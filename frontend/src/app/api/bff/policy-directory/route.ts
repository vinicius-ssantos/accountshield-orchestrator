import { readFrontendEnvironment } from "@/config/environment";
import { getPoliciesDataSource } from "@/features/policies/get-data-source";
import { handlePolicyDirectoryRequest } from "@/server/bff/policy-directory";
import type { PolicyDirectoryService } from "@/server/bff/policy-directory-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureDirectoryService(): PolicyDirectoryService {
  const dataSource = getPoliciesDataSource();
  return {
    async search() {
      return dataSource.search();
    },
  };
}

function configuredService(): PolicyDirectoryService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureDirectoryService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handlePolicyDirectoryRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handlePolicyDirectoryRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handlePolicyDirectoryRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handlePolicyDirectoryRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handlePolicyDirectoryRequest(request, configuredService());
}
