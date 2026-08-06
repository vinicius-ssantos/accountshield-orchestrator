// Deliberately does not import the literal "server-only" npm package, for the same reason as
// every other mutation module: the real architecture boundary (ARCH001) is enforced by this
// file's path under src/server/, and omitting the import lets this file's handlers be exercised
// directly by vitest.
import { readFrontendEnvironment } from "@/config/environment";

import { assertRequestPolicy, BffError, readJsonObject, resolveCorrelationId, toProblemDetails } from "./foundation";
import type { BffTelemetrySink } from "./observability";
import { startBffTelemetry } from "./observability";
import {
  AccountShieldEvidenceExportClient,
  parseEvidenceExportInput,
  type EvidenceExportService,
} from "./evidence-export-core";
import { requireOperatorSession } from "./session/require-session";

const CACHE_CONTROL = "private, no-store, max-age=0, must-revalidate";
const MAX_REQUEST_BYTES = 1024;

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

/**
 * Export is a real mutation: it appends one row to audit.evidence_export_log (ADR 0028). Unlike
 * verify (read-only, resolveOperatorToken), this always requires a genuine operator session --
 * no env-token fallback -- with CSRF/origin validation enforced unconditionally.
 */
export function createEvidenceExportClient(
  request: Request,
  source: Readonly<Record<string, string | undefined>> = process.env,
): AccountShieldEvidenceExportClient {
  const environment = readFrontendEnvironment(source, "runtime");
  if (environment.dataSource !== "live" || !environment.apiUrl) {
    throw new BffError("UPSTREAM_UNAVAILABLE", 503, "Live evidence export is not configured.", true);
  }

  const { backendToken } = requireOperatorSession(request, source);

  return new AccountShieldEvidenceExportClient({
    origin: environment.apiUrl,
    operatorToken: backendToken,
    timeoutMs: boundedInteger("ACCOUNTSHIELD_BFF_TIMEOUT_MS", source.ACCOUNTSHIELD_BFF_TIMEOUT_MS, 4_000, 100, 15_000),
    maxResponseBytes: boundedInteger(
      "ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES",
      source.ACCOUNTSHIELD_BFF_MAX_RESPONSE_BYTES,
      256 * 1024,
      4 * 1024,
      512 * 1024,
    ),
  });
}

export async function handleEvidenceExportRequest(
  request: Request,
  service?: EvidenceExportService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({ useCase: "evidence_export", correlationId, sink: telemetrySink });

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["POST"],
      allowedContentTypes: ["application/json"],
      maxBodyBytes: MAX_REQUEST_BYTES,
    });
    const body = await readJsonObject(request, MAX_REQUEST_BYTES);
    const input = parseEvidenceExportInput(body);
    const resolvedService = service ?? createEvidenceExportClient(request, source);
    const result = await resolvedService.export(input, correlationId, request.signal);
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
