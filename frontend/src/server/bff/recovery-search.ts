import "server-only";

import { readFrontendEnvironment } from "@/config/environment";

import {
  assertRequestPolicy,
  BffError,
  readJsonObject,
  resolveCorrelationId,
  toProblemDetails,
} from "./foundation";
import type { BffTelemetrySink } from "./observability";
import { startBffTelemetry } from "./observability";
import {
  AccountShieldRecoverySearchClient,
  parseRecoverySearchInput,
  type RecoverySearchInput,
  type RecoverySearchResult,
  type RecoverySearchService,
} from "./recovery-search-core";

const CACHE_CONTROL = "private, no-store, max-age=0, must-revalidate";
const MAX_REQUEST_BYTES = 4 * 1024;

function boundedInteger(
  name: string,
  value: string | undefined,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  const parsed = value ? Number.parseInt(value, 10) : fallback;
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}.`);
  }
  return parsed;
}

export function createRecoverySearchClient(
  source: Readonly<Record<string, string | undefined>> = process.env,
): AccountShieldRecoverySearchClient {
  const environment = readFrontendEnvironment(source, "runtime");
  if (environment.dataSource !== "live" || !environment.apiUrl) {
    throw new BffError("UPSTREAM_UNAVAILABLE", 503, "Live recovery search is not configured.", true);
  }

  const operatorToken = source.ACCOUNTSHIELD_OPERATOR_TOKEN?.trim();
  if (!operatorToken) {
    throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  }

  return new AccountShieldRecoverySearchClient({
    origin: environment.apiUrl,
    operatorToken,
    timeoutMs: boundedInteger(
      "ACCOUNTSHIELD_BFF_TIMEOUT_MS",
      source.ACCOUNTSHIELD_BFF_TIMEOUT_MS,
      4_000,
      100,
      15_000,
    ),
    maxResponseBytes: boundedInteger(
      "ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES",
      source.ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES,
      128 * 1024,
      4 * 1024,
      512 * 1024,
    ),
  });
}

export async function searchLiveRecoveries(
  input: RecoverySearchInput,
  correlationId = resolveCorrelationId(undefined),
  signal?: AbortSignal,
): Promise<RecoverySearchResult> {
  return createRecoverySearchClient().search(input, correlationId, signal);
}

export async function handleRecoverySearchRequest(
  request: Request,
  service?: RecoverySearchService,
  telemetrySink?: BffTelemetrySink,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({
    useCase: "recovery_search",
    correlationId,
    sink: telemetrySink,
  });

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["POST"],
      allowedContentTypes: ["application/json"],
      maxBodyBytes: MAX_REQUEST_BYTES,
    });
    const body = await readJsonObject(request, MAX_REQUEST_BYTES);
    const input = parseRecoverySearchInput(body);
    const result = await (service ?? createRecoverySearchClient()).search(
      input,
      correlationId,
      request.signal,
    );
    telemetry.succeed(200);

    return Response.json(result, {
      status: 200,
      headers: {
        "cache-control": CACHE_CONTROL,
        "x-correlation-id": correlationId,
      },
    });
  } catch (error) {
    const problem = toProblemDetails(error, correlationId);
    if (request.signal.aborted) telemetry.cancel();
    else telemetry.fail(error, problem.status);

    const headers = new Headers({
      "cache-control": CACHE_CONTROL,
      "content-type": "application/problem+json",
      "x-correlation-id": correlationId,
    });
    if (problem.code === "METHOD_NOT_ALLOWED") headers.set("allow", "POST");

    return new Response(JSON.stringify(problem), {
      status: problem.status,
      headers,
    });
  }
}
