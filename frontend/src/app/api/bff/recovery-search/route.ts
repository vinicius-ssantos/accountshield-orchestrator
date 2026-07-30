import { readFrontendEnvironment } from "@/config/environment";
import { getRecoveriesDataSource } from "@/features/recoveries/get-data-source";
import type {
  RecoveryClassification,
  RecoveryEventType,
  RecoveryReviewState,
  RecoverySearchCriteria,
  RecoveryStatus,
} from "@/features/recoveries/types";
import { handleRecoverySearchRequest } from "@/server/bff/recovery-search";
import type {
  RecoverySearchInput,
  RecoverySearchService,
} from "@/server/bff/recovery-search-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureSearchService(): RecoverySearchService {
  const dataSource = getRecoveriesDataSource();
  return {
    async search(input: RecoverySearchInput) {
      const criteria: RecoverySearchCriteria = {
        status: input.status as RecoveryStatus | undefined,
        classification: input.classification as RecoveryClassification | undefined,
        eventType: input.eventType as RecoveryEventType | undefined,
        reviewState: input.reviewState as RecoveryReviewState | undefined,
        initiatedFrom: input.initiatedFrom,
        initiatedTo: input.initiatedTo,
        eligibleFrom: input.eligibleFrom,
        eligibleTo: input.eligibleTo,
        minimumRiskScore: input.minimumRiskScore,
        maximumRiskScore: input.maximumRiskScore,
        cursor: input.cursor,
        pageSize: input.pageSize,
      };
      return dataSource.search(criteria);
    },
  };
}

function configuredService(): RecoverySearchService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureSearchService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleRecoverySearchRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handleRecoverySearchRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handleRecoverySearchRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handleRecoverySearchRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handleRecoverySearchRequest(request, configuredService());
}
