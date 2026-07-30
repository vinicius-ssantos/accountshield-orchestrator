import { readFrontendEnvironment } from "@/config/environment";
import { getDecisionsDataSource } from "@/features/decisions/get-data-source";
import { BffError } from "@/server/bff/foundation";
import { handleDecisionTimelineRequest } from "@/server/bff/decision-timeline";
import type {
  DecisionTimelineInput,
  DecisionTimelineService,
} from "@/server/bff/decision-timeline-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureTimelineService(): DecisionTimelineService {
  const dataSource = getDecisionsDataSource();
  return {
    async investigate(input: DecisionTimelineInput) {
      try {
        return await dataSource.investigate(input.decisionReference);
      } catch {
        throw new BffError("NOT_FOUND", 404, "The decision investigation was not found.");
      }
    },
  };
}

function configuredService(): DecisionTimelineService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureTimelineService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleDecisionTimelineRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handleDecisionTimelineRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handleDecisionTimelineRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handleDecisionTimelineRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handleDecisionTimelineRequest(request, configuredService());
}
