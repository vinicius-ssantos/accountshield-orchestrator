import "server-only";

import { readFrontendEnvironment } from "@/config/environment";

import {
  BffError,
  assertRequestPolicy,
  createSafeLogRecord,
  resolveCorrelationId,
  toProblemDetails,
} from "./foundation";
import {
  AccountShieldReadClient,
  RuntimeStatusService,
} from "./runtime-status-core";

const CACHE_CONTROL = "private, no-store, max-age=0, must-revalidate";
const DEFAULT_TIMEOUT_MS = 2_000;
const DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024;

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

export function createRuntimeStatusService(
  source: Readonly<Record<string, string | undefined>> = process.env,
): RuntimeStatusService {
  const environment = readFrontendEnvironment(source, "runtime");

  if (environment.dataSource === "fixtures") {
    return new RuntimeStatusService({ source: "fixtures" });
  }

  if (!environment.apiUrl) {
    throw new Error("Live BFF mode requires ACCOUNTSHIELD_API_URL.");
  }

  const client = new AccountShieldReadClient({
    origin: environment.apiUrl,
    timeoutMs: boundedInteger(
      "ACCOUNTSHIELD_BFF_TIMEOUT_MS",
      source.ACCOUNTSHIELD_BFF_TIMEOUT_MS,
      DEFAULT_TIMEOUT_MS,
      100,
      10_000,
    ),
    maxResponseBytes: boundedInteger(
      "ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES",
      source.ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES,
      DEFAULT_MAX_RESPONSE_BYTES,
      1_024,
      256 * 1_024,
    ),
  });

  return new RuntimeStatusService({ source: "live", client });
}

export async function handleRuntimeStatusRequest(
  request: Request,
  service: RuntimeStatusService = createRuntimeStatusService(),
): Promise<Response> {
  const correlationId = resolveCorrelationId(
    request.headers.get("x-correlation-id"),
  );

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["GET"],
      maxBodyBytes: 0,
    });

    const view = await service.getStatus(correlationId, request.signal);
    return Response.json(view, {
      status: 200,
      headers: {
        "cache-control": CACHE_CONTROL,
        "x-correlation-id": correlationId,
      },
    });
  } catch (error) {
    const safeContext = {
      method: request.method,
      pathname: new URL(request.url).pathname,
      errorCode: error instanceof BffError ? error.code : "INTERNAL_ERROR",
    };
    console.error(
      JSON.stringify(
        createSafeLogRecord(
          "accountshield.bff.runtime_status_failed",
          correlationId,
          safeContext,
        ),
      ),
    );

    const problem = toProblemDetails(error, correlationId);
    const headers = new Headers({
      "cache-control": CACHE_CONTROL,
      "content-type": "application/problem+json",
      "x-correlation-id": correlationId,
    });
    if (problem.code === "METHOD_NOT_ALLOWED") {
      headers.set("allow", "GET");
    }

    return new Response(JSON.stringify(problem), {
      status: problem.status,
      headers,
    });
  }
}
