import { BffError } from "./foundation";

export interface EvidenceVerifyResult {
  valid: boolean;
  problems: readonly string[];
}

export interface EvidenceVerifyService {
  verify(
    bundle: Record<string, unknown>,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<EvidenceVerifyResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function malformed(): never {
  throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.");
}

// The bundle a caller sends back for verification is opaque to the BFF -- it is whatever this
// same operator exported a moment earlier (or received from someone else). There is no server
// secret or authorization decision embedded in its shape, so this only bounds it to "an object
// with the two top-level sections", not a full field-by-field re-validation like the export
// response gets; the backend's own signature check is the real gate.
export function parseEvidenceVerifyInput(body: Record<string, unknown>): Record<string, unknown> {
  if (!isRecord(body.manifest) || !isRecord(body.content)) {
    throw new BffError("INVALID_REQUEST", 400, "The evidence bundle is invalid.");
  }
  return body;
}

export function parseEvidenceVerifyResult(value: unknown): EvidenceVerifyResult {
  if (!isRecord(value)) malformed();
  if (typeof value.valid !== "boolean") malformed();
  if (!Array.isArray(value.problems)) malformed();
  const problems = value.problems.map((problem) => {
    if (typeof problem !== "string" || problem.length > 512) malformed();
    return problem;
  });
  return { valid: value.valid, problems };
}

function mapUpstreamFailure(status: number): never {
  if (status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  if (status === 403) throw new BffError("FORBIDDEN", 403, "Evidence verification is not permitted.");
  if (status === 400) throw new BffError("INVALID_REQUEST", 400, "The evidence bundle is invalid.");
  throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Evidence verification is temporarily unavailable.", true);
}

export interface EvidenceVerifyClientConfig {
  origin: string;
  operatorToken: string;
  timeoutMs: number;
  maxResponseBytes: number;
  maxRequestBytes: number;
}

export class AccountShieldEvidenceVerifyClient implements EvidenceVerifyService {
  constructor(private readonly config: EvidenceVerifyClientConfig) {}

  async verify(
    bundle: Record<string, unknown>,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<EvidenceVerifyResult> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);
    if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true });
    try {
      const requestBody = JSON.stringify(bundle);
      if (requestBody.length > this.config.maxRequestBytes) {
        throw new BffError("PAYLOAD_TOO_LARGE", 413, "The evidence bundle is too large to verify.");
      }

      const response = await fetch(`${this.config.origin}/api/v1/evidence/verify`, {
        method: "POST",
        headers: {
          accept: "application/json",
          authorization: `Bearer ${this.config.operatorToken}`,
          "content-type": "application/json",
          "x-correlation-id": correlationId,
        },
        body: requestBody,
        cache: "no-store",
        signal: controller.signal,
      });

      if (!response.ok) mapUpstreamFailure(response.status);

      const declaredLength = Number.parseInt(response.headers.get("content-length") ?? "", 10);
      if (Number.isFinite(declaredLength) && declaredLength > this.config.maxResponseBytes) malformed();
      const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
      if (contentType !== "application/json") malformed();
      const raw = new Uint8Array(await response.arrayBuffer());
      if (raw.byteLength > this.config.maxResponseBytes) malformed();

      let parsed: unknown;
      try {
        parsed = JSON.parse(new TextDecoder().decode(raw));
      } catch (error) {
        throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
          cause: error,
        });
      }
      return parseEvidenceVerifyResult(parsed);
    } catch (error) {
      if (error instanceof BffError) throw error;
      if (controller.signal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "Evidence verification timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Evidence verification is temporarily unavailable.", true, {
        cause: error,
      });
    } finally {
      clearTimeout(timeout);
    }
  }
}
