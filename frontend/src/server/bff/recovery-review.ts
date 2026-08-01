// Deliberately does not import the literal "server-only" npm package, for the same reason as
// session.ts and require-session.ts: the real architecture boundary (ARCH001) is enforced by
// this file's path under src/server/, and omitting the import lets this file's handlers be
// exercised directly by vitest.
import { readFrontendEnvironment } from "@/config/environment";

import { assertRequestPolicy, BffError, readJsonObject, resolveCorrelationId, toProblemDetails } from "./foundation";
import type { BffTelemetrySink } from "./observability";
import { startBffTelemetry } from "./observability";
import {
  AccountShieldRecoveryReviewClient,
  parseReviewSubmissionInput,
  parseStepUpRequestInput,
  parseVerifyStepUpInput,
  type RecoveryReviewService,
  type ReviewSubmissionResult,
  type StepUpResult,
  type VerifyStepUpResult,
} from "./recovery-review-core";
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
 * Mutations always require a genuine operator session -- unlike read routes, there is no
 * fixtures/dev env-token fallback here (requireOperatorSession, not resolveOperatorToken). CSRF
 * and origin validation are enforced unconditionally by requireOperatorSession for any
 * non-GET/HEAD request.
 */
export function createRecoveryReviewClient(
  request: Request,
  source: Readonly<Record<string, string | undefined>> = process.env,
): AccountShieldRecoveryReviewClient {
  const environment = readFrontendEnvironment(source, "runtime");
  if (environment.dataSource !== "live" || !environment.apiUrl) {
    throw new BffError("UPSTREAM_UNAVAILABLE", 503, "Live recovery review is not configured.", true);
  }

  const { backendToken } = requireOperatorSession(request, source);

  return new AccountShieldRecoveryReviewClient({
    origin: environment.apiUrl,
    operatorToken: backendToken,
    timeoutMs: boundedInteger("ACCOUNTSHIELD_BFF_TIMEOUT_MS", source.ACCOUNTSHIELD_BFF_TIMEOUT_MS, 4_000, 100, 15_000),
  });
}

async function handle<TResult>(
  useCase: string,
  request: Request,
  service: RecoveryReviewService | undefined,
  telemetrySink: BffTelemetrySink | undefined,
  source: Readonly<Record<string, string | undefined>>,
  run: (service: RecoveryReviewService, body: Record<string, unknown>, correlationId: string) => Promise<TResult>,
): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));
  const telemetry = startBffTelemetry({ useCase, correlationId, sink: telemetrySink });

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["POST"],
      allowedContentTypes: ["application/json"],
      maxBodyBytes: MAX_REQUEST_BYTES,
    });
    const body = await readJsonObject(request, MAX_REQUEST_BYTES);
    const resolvedService = service ?? createRecoveryReviewClient(request, source);
    const result = await run(resolvedService, body, correlationId);
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

export async function handleStepUpRequest(
  request: Request,
  service?: RecoveryReviewService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  return handle<StepUpResult>(
    "recovery_review_step_up",
    request,
    service,
    telemetrySink,
    source,
    (svc, body, correlationId) => svc.requestStepUp(parseStepUpRequestInput(body), correlationId, request.signal),
  );
}

export async function handleVerifyStepUpRequest(
  request: Request,
  service?: RecoveryReviewService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  return handle<VerifyStepUpResult>(
    "recovery_review_verify",
    request,
    service,
    telemetrySink,
    source,
    (svc, body, correlationId) => svc.verifyStepUp(parseVerifyStepUpInput(body), correlationId, request.signal),
  );
}

export async function handleReviewSubmissionRequest(
  request: Request,
  service?: RecoveryReviewService,
  telemetrySink?: BffTelemetrySink,
  source: Readonly<Record<string, string | undefined>> = process.env,
): Promise<Response> {
  return handle<ReviewSubmissionResult>(
    "recovery_review_submit",
    request,
    service,
    telemetrySink,
    source,
    (svc, body, correlationId) => svc.submitReview(parseReviewSubmissionInput(body), correlationId, request.signal),
  );
}
