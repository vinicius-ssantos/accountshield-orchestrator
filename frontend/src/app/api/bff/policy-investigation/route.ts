import { readFrontendEnvironment } from "@/config/environment";
import { getPoliciesDataSource } from "@/features/policies/get-data-source";
import { BffError } from "@/server/bff/foundation";
import { handlePolicyInvestigationRequest } from "@/server/bff/policy-investigation";
import type {
  PolicyInvestigationInput,
  PolicyInvestigationService,
} from "@/server/bff/policy-investigation-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureInvestigationService(): PolicyInvestigationService {
  const dataSource = getPoliciesDataSource();
  return {
    async investigate(input: PolicyInvestigationInput) {
      try {
        return await dataSource.investigate(input.policyKey);
      } catch {
        throw new BffError("NOT_FOUND", 404, "The policy investigation was not found.");
      }
    },
  };
}

function configuredService(): PolicyInvestigationService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureInvestigationService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handlePolicyInvestigationRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handlePolicyInvestigationRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handlePolicyInvestigationRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handlePolicyInvestigationRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handlePolicyInvestigationRequest(request, configuredService());
}
