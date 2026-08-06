import { readFrontendEnvironment } from "@/config/environment";
import { getRecoveriesDataSource } from "@/features/recoveries/get-data-source";
import { BffError } from "@/server/bff/foundation";
import { handleRecoveryDetailRequest } from "@/server/bff/recovery-detail";
import type {
  RecoveryDetailInput,
  RecoveryDetailService,
} from "@/server/bff/recovery-detail-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureDetailService(): RecoveryDetailService {
  const dataSource = getRecoveriesDataSource();
  return {
    async investigate(input: RecoveryDetailInput) {
      try {
        return await dataSource.investigate(input.recoveryReference);
      } catch {
        throw new BffError("NOT_FOUND", 404, "The recovery investigation was not found.");
      }
    },
  };
}

function configuredService(): RecoveryDetailService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureDetailService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleRecoveryDetailRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handleRecoveryDetailRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handleRecoveryDetailRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handleRecoveryDetailRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handleRecoveryDetailRequest(request, configuredService());
}
