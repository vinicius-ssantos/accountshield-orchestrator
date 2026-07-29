import { readFrontendEnvironment } from "@/config/environment";
import { getDecisionsDataSource } from "@/features/decisions/get-data-source";
import type {
  DecisionEventType,
  DecisionOutcome,
  DecisionRiskBand,
  DecisionSearchCriteria,
} from "@/features/decisions/types";
import {
  handleDecisionSearchRequest,
} from "@/server/bff/decision-search";
import type {
  DecisionSearchInput,
  DecisionSearchService,
} from "@/server/bff/decision-search-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureSearchService(): DecisionSearchService {
  const dataSource = getDecisionsDataSource();
  return {
    async search(input: DecisionSearchInput) {
      const criteria: DecisionSearchCriteria = {
        correlationId: input.correlationId,
        eventType: input.eventType as DecisionEventType | undefined,
        outcome: input.outcome as DecisionOutcome | undefined,
        riskBand: input.riskBand as DecisionRiskBand | undefined,
        policyVersion: input.policyVersion,
        decidedFrom: input.decidedFrom,
        decidedTo: input.decidedTo,
        cursor: input.cursor,
        pageSize: input.pageSize,
      };
      return dataSource.search(criteria);
    },
  };
}

function configuredService(): DecisionSearchService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureSearchService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request, configuredService());
}
