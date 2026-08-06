import "server-only";

import { readFrontendEnvironment } from "@/config/environment";

import { assertRequestPolicy, BffError, readJsonObject, resolveCorrelationId, toProblemDetails } from "./foundation";
import type { BffTelemetrySink } from "./observability";
import { startBffTelemetry } from "./observability";
import {
  AccountShieldEvidenceVerifyClient,
  parseEvidenceVerifyInput,
  type EvidenceVerifyService,
} from "./evidence-verify-core";
import { resolveOperatorToken } from "./session/require-session";

const CACHE_CONTROL = "private, no-store, max-age=0, must-revalidate";
const MAX_REQUEST_BYTES = 256 * 1024;

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

// Verify recomputes a hash and checks a signature -- it never reads or writes any persisted
// state (EvidenceBundleApplicationService.verify is @Transactional(readOnly = true) and touches
// no repository). It is therefore classified as a read, like decision replay: resolveOperatorToken
// (session with the fixtures/dev env-token fallback), not requireOperatorSession.
export function createEvidenceVerifyClient(
  request: Request,
  source: Readonly<Record<string, string | undefined>> = process.env,
): AccountShieldEvidenceVerifyClient {
  const environment = readFrontendEnvironment(source, "runtime");
  if (environment.dataSource !== "live" || !environment.apiUrl) {
    throw new BffError("UPSTREAM_UNAVAILABLE", 503, "Live evidence verification is not configured.", true);
  }

  const operatorToken = resolveOperatorToken(request, source);

  return new AccountShieldEvidenceVerifyClient({
    origin: environment.apiUrl,
    operatorToken,
    timeoutMs: boundedInteger("ACCOUNTSHIELD_BFF_TIMEOUT_MS", source.ACCOUNTSHIELD_BFF_TIMEOUT_MS, 4_000, 100, 15_000),
    maxResponseBytes: boundedInteger(
      "ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES",
      source.ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES,
      256 * 1024,
      4 * 1024,
      512 * 1024,
    ),
    maxRequestBytes: MAX_REQUEST_BYTES,
  });
}

export async function handleEvidenceVerifyRequest(
  request: Request,
  service?: EvidenceVerifyService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({ useCase: "evidence_verify", correlationId, sink: telemetrySink });

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["POST"],
      allowedContentTypes: ["application/json"],
      maxBodyBytes: MAX_REQUEST_BYTES,
    });
    const body = await readJsonObject(request, MAX_REQUEST_BYTES);
    const bundle = parseEvidenceVerifyInput(body);
    const resolvedService = service ?? createEvidenceVerifyClient(request, source);
    const result = await resolvedService.verify(bundle, correlationId, request.signal);
    telemetry.succeed(200);

    return Response.json(result, {
      status: 200,
      headers: { "cache-control": CACHE_CONTROL, "x-correlation-id": correlationId },
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

    return new Response(JSON.stringify(problem), { status: problem.status, headers });
  }
}
