import type { BffErrorCode, BffProblemDetails } from "./model";

const CORRELATION_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const REDACTED = "[REDACTED]";
const SENSITIVE_KEY_PATTERN =
  /authorization|cookie|token|secret|password|credential|challenge|account.?id|user.?id|email|phone|raw/i;
const BEARER_PATTERN = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const JWT_PATTERN = /\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g;

export class BffError extends Error {
  constructor(
    public readonly code: BffErrorCode,
    public readonly status: number,
    public readonly title: string,
    public readonly retryable = false,
    options?: ErrorOptions,
  ) {
    super(title, options);
    this.name = "BffError";
  }
}

export function resolveCorrelationId(
  candidate: string | null | undefined,
  generate: () => string = () => `bff_${crypto.randomUUID().replaceAll("-", "")}`,
): string {
  const normalized = candidate?.trim();
  if (normalized && CORRELATION_ID_PATTERN.test(normalized)) {
    return normalized;
  }
  return generate();
}

export function isValidCorrelationId(value: string): boolean {
  return CORRELATION_ID_PATTERN.test(value);
}

export function toProblemDetails(
  error: unknown,
  correlationId: string,
): BffProblemDetails {
  const safeError =
    error instanceof BffError
      ? error
      : new BffError(
          "INTERNAL_ERROR",
          500,
          "The request could not be completed.",
          false,
        );

  return {
    type: `https://accountshield.dev/problems/${safeError.code.toLowerCase().replaceAll("_", "-")}`,
    title: safeError.title,
    status: safeError.status,
    code: safeError.code,
    correlationId,
    retryable: safeError.retryable,
  };
}

export interface RequestPolicy {
  allowedMethods: readonly string[];
  allowedContentTypes?: readonly string[];
  maxBodyBytes?: number;
}

export function assertRequestPolicy(
  request: Request,
  policy: RequestPolicy,
): void {
  const method = request.method.toUpperCase();
  if (!policy.allowedMethods.includes(method)) {
    throw new BffError(
      "METHOD_NOT_ALLOWED",
      405,
      "This operation is not supported.",
    );
  }

  const contentLengthHeader = request.headers.get("content-length");
  const contentLength = contentLengthHeader
    ? Number.parseInt(contentLengthHeader, 10)
    : 0;
  if (!Number.isFinite(contentLength) || contentLength < 0) {
    throw new BffError("INVALID_REQUEST", 400, "The request is invalid.");
  }

  if (
    policy.maxBodyBytes !== undefined &&
    contentLength > policy.maxBodyBytes
  ) {
    throw new BffError(
      "PAYLOAD_TOO_LARGE",
      413,
      "The request body is too large.",
    );
  }

  const hasBody = contentLength > 0 || request.body !== null;
  if (hasBody && policy.allowedContentTypes) {
    const contentType = request.headers
      .get("content-type")
      ?.split(";", 1)[0]
      ?.trim()
      .toLowerCase();
    if (!contentType || !policy.allowedContentTypes.includes(contentType)) {
      throw new BffError(
        "UNSUPPORTED_MEDIA_TYPE",
        415,
        "The request content type is not supported.",
      );
    }
  }
}

export async function readJsonObject(
  request: Request,
  maxBodyBytes: number,
): Promise<Record<string, unknown>> {
  assertRequestPolicy(request, {
    allowedMethods: [request.method.toUpperCase()],
    allowedContentTypes: ["application/json", "application/problem+json"],
    maxBodyBytes,
  });

  const body = new Uint8Array(await request.arrayBuffer());
  if (body.byteLength > maxBodyBytes) {
    throw new BffError(
      "PAYLOAD_TOO_LARGE",
      413,
      "The request body is too large.",
    );
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(body));
  } catch {
    throw new BffError("INVALID_REQUEST", 400, "The request body is invalid.");
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new BffError("INVALID_REQUEST", 400, "The request body is invalid.");
  }

  return parsed as Record<string, unknown>;
}

function redactString(value: string): string {
  return value.replace(BEARER_PATTERN, REDACTED).replace(JWT_PATTERN, REDACTED);
}

export function redactForLog(value: unknown): unknown {
  if (typeof value === "string") {
    return redactString(value);
  }
  if (Array.isArray(value)) {
    return value.map(redactForLog);
  }
  if (!value || typeof value !== "object") {
    return value;
  }

  const redacted: Record<string, unknown> = {};
  for (const [key, nestedValue] of Object.entries(value)) {
    redacted[key] = SENSITIVE_KEY_PATTERN.test(key)
      ? REDACTED
      : redactForLog(nestedValue);
  }
  return redacted;
}

export function createSafeLogRecord(
  event: string,
  correlationId: string,
  context: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    event,
    correlationId,
    context: redactForLog(context),
  };
}
