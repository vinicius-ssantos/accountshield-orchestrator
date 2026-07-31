import { readFrontendEnvironment } from "@/config/environment";
import type { OutboxSearchFilters, OutboxStatus } from "@/features/outbox/types";
import { getOutboxDataSource } from "@/features/outbox/get-data-source";
import { handleOutboxSearchRequest } from "@/server/bff/outbox-search";
import type { OutboxSearchInput, OutboxSearchService } from "@/server/bff/outbox-search-core";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function fixtureSearchService(): OutboxSearchService {
  const dataSource = getOutboxDataSource();
  return {
    async search(input: OutboxSearchInput) {
      // parseOutboxSearchInput already validated every status against the known enum before this
      // point is reached, so the cast reflects a runtime guarantee, not an unchecked assumption.
      const filters: OutboxSearchFilters = {
        statuses: input.statuses as readonly OutboxStatus[] | undefined,
        eventType: input.eventType,
        occurredFrom: input.occurredFrom,
        occurredTo: input.occurredTo,
        minAttemptCount: input.minAttemptCount,
        maxAttemptCount: input.maxAttemptCount,
        cursor: input.cursor,
        pageSize: input.pageSize,
      };
      return dataSource.search(filters);
    },
  };
}

function configuredService(): OutboxSearchService | undefined {
  const environment = readFrontendEnvironment(process.env, "runtime");
  return environment.dataSource === "fixtures" ? fixtureSearchService() : undefined;
}

export async function POST(request: Request): Promise<Response> {
  return handleOutboxSearchRequest(request, configuredService());
}

export async function GET(request: Request): Promise<Response> {
  return handleOutboxSearchRequest(request, configuredService());
}

export async function PUT(request: Request): Promise<Response> {
  return handleOutboxSearchRequest(request, configuredService());
}

export async function PATCH(request: Request): Promise<Response> {
  return handleOutboxSearchRequest(request, configuredService());
}

export async function DELETE(request: Request): Promise<Response> {
  return handleOutboxSearchRequest(request, configuredService());
}
