import { readFrontendEnvironment } from "@/config/environment";
import { getDecisionsDataSource } from "@/features/decisions/get-data-source";
import { BffError } from "@/server/bff/foundation";
import { handleDecisionReplayRequest } from "@/server/bff/decision-replay";
import type {
  DecisionReplayInput,
  DecisionReplayService,
} from "@/server/bff/decision-replay-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureReplayService(): DecisionReplayService {
  const dataSource = getDecisionsDataSource();
  return {
    async replay(input: DecisionReplayInput) {
      try {
        return await dataSource.replay(input.decisionReference);
      } catch {
        throw new BffError("NOT_FOUND", 404, "The decision replay was not found.");
      }
    },
  };
}

function configuredService(): DecisionReplayService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureReplayService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleDecisionReplayRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handleDecisionReplayRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handleDecisionReplayRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handleDecisionReplayRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handleDecisionReplayRequest(request, configuredService());
}
