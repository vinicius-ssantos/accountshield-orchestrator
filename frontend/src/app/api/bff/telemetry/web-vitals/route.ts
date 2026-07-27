import {
  BffError,
  assertRequestPolicy,
  readJsonObject,
  resolveCorrelationId,
  toProblemDetails,
} from "@/server/bff/foundation";
import { normalizeWebVital } from "@/features/telemetry/web-vitals-core";

const CACHE_CONTROL = "private, no-store, max-age=0, must-revalidate";
const MAX_BODY_BYTES = 1_024;

function configuredSampleRate(): number {
  const fallback = process.env.NODE_ENV === "production" ? 0.1 : 1;
  const raw = process.env.ACCOUNTSHIELD_WEB_VITALS_SAMPLE_RATE;
  if (!raw) return fallback;

  const parsed = Number.parseFloat(raw);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    throw new Error("ACCOUNTSHIELD_WEB_VITALS_SAMPLE_RATE must be between 0 and 1.");
  }
  return parsed;
}

export async function POST(request: Request): Promise<Response> {
  const correlationId = resolveCorrelationId(request.headers.get("x-correlation-id"));

  try {
    assertRequestPolicy(request, {
      allowedMethods: ["POST"],
      allowedContentTypes: ["application/json"],
      maxBodyBytes: MAX_BODY_BYTES,
    });

    const payload = await readJsonObject(request, MAX_BODY_BYTES);
    const metric = normalizeWebVital(payload);
    if (!metric) {
      throw new BffError("INVALID_REQUEST", 400, "The telemetry payload is invalid.");
    }

    if (Math.random() < configuredSampleRate()) {
      console.info(JSON.stringify({
        event: "accountshield.frontend.web_vital",
        metric: metric.name,
        value: metric.value,
        rating: metric.rating,
        navigationType: metric.navigationType,
        route: metric.route,
      }));
    }

    return new Response(null, {
      status: 202,
      headers: { "cache-control": CACHE_CONTROL },
    });
  } catch (error) {
    const problem = toProblemDetails(error, correlationId);
    return new Response(JSON.stringify(problem), {
      status: problem.status,
      headers: {
        "cache-control": CACHE_CONTROL,
        "content-type": "application/problem+json",
        "x-correlation-id": correlationId,
      },
    });
  }
}
