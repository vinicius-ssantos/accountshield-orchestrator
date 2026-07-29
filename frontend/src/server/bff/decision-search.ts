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
  AccountShieldDecisionSearchClient,
  parseDecisionSearchInput,
  type DecisionSearchInput,
  type DecisionSearchResult,
} from "./decision-search-core";

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

export function createDecisionSearchClient(
  source: Readonly<Record<string, string | undefined>> = process.env,
): AccountShieldDecisionSearchClient {
  const environment = readFrontendEnvironment(source, "runtime");
  if (environment.dataSource !== "live" || !environment.apiUrl) {
    throw new BffError("UPSTREAM_UNAVAILABLE", 503, "Live decision search is not configured.", true);
  }

  const operatorToken = source.ACCOUNTSHIELD_OPERATOR_TOKEN?.trim();
  if (!operatorToken) {
    throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  }

  return new AccountShieldDecisionSearchClient({
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

export async function searchLiveDecisions(
  input: DecisionSearchInput,
  correlationId = resolveCorrelationId(undefined),
  signal?: AbortSignal,
): Promise<DecisionSearchResult> {
  return createDecisionSearchClient().search(input, correlationId, signal);
}

export async function handleDecisionSearchRequest(
  request: Request,
  client?: AccountShieldDecisionSearchClient,
  telemetrySink?: BffTelemetrySink,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({
    useCase: "decision_search",
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
    const input = parseDecisionSearchInput(body);
    const result = await (client ?? createDecisionSearchClient()).search(
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
