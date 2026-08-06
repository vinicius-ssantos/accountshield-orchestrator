import { BffError } from "./foundation";
import type {
  RuntimeAvailability,
  RuntimeStatusSource,
  RuntimeStatusView,
} from "./model";

export type FetchLike = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

export interface AccountShieldReadClientOptions {
  origin: string;
  timeoutMs: number;
  maxResponseBytes: number;
  fetchImpl?: FetchLike;
}

interface RuntimeHealthPayload {
  status: string;
}

function mapUpstreamFailure(status: number): BffError {
  if (status === 401) {
    return new BffError(
      "UNAUTHORIZED",
      401,
      "Authentication is required.",
    );
  }
  if (status === 403) {
    return new BffError(
      "FORBIDDEN",
      403,
      "The operation is not permitted.",
    );
  }
  return new BffError(
    "UPSTREAM_UNAVAILABLE",
    503,
    "The AccountShield service is unavailable.",
    true,
  );
}

function responseByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

export class AccountShieldReadClient {
  private readonly origin: string;
  private readonly timeoutMs: number;
  private readonly maxResponseBytes: number;
  private readonly fetchImpl: FetchLike;

  constructor(options: AccountShieldReadClientOptions) {
    this.origin = new URL(options.origin).origin;
    this.timeoutMs = options.timeoutMs;
    this.maxResponseBytes = options.maxResponseBytes;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  async getRuntimeHealth(
    correlationId: string,
    callerSignal?: AbortSignal,
  ): Promise<RuntimeHealthPayload> {
    const controller = new AbortController();
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, this.timeoutMs);
    const abortFromCaller = () => controller.abort();
    callerSignal?.addEventListener("abort", abortFromCaller, { once: true });

    try {
      const response = await this.fetchImpl(
        new URL("/actuator/health", this.origin),
        {
          method: "GET",
          headers: {
            accept: "application/json",
            "x-correlation-id": correlationId,
          },
          cache: "no-store",
          redirect: "error",
          signal: controller.signal,
        },
      );

      const declaredLength = Number.parseInt(
        response.headers.get("content-length") ?? "0",
        10,
      );
      if (
        Number.isFinite(declaredLength) &&
        declaredLength > this.maxResponseBytes
      ) {
        throw new BffError(
          "UPSTREAM_MALFORMED_RESPONSE",
          502,
          "The AccountShield service returned an invalid response.",
        );
      }

      const rawBody = await response.text();
      if (responseByteLength(rawBody) > this.maxResponseBytes) {
        throw new BffError(
          "UPSTREAM_MALFORMED_RESPONSE",
          502,
          "The AccountShield service returned an invalid response.",
        );
      }

      if (!response.ok) {
        throw mapUpstreamFailure(response.status);
      }

      const contentType = response.headers
        .get("content-type")
        ?.split(";", 1)[0]
        ?.trim()
        .toLowerCase();
      if (
        contentType !== "application/json" &&
        !contentType?.endsWith("+json")
      ) {
        throw new BffError(
          "UPSTREAM_MALFORMED_RESPONSE",
          502,
          "The AccountShield service returned an invalid response.",
        );
      }

      let payload: unknown;
      try {
        payload = rawBody ? JSON.parse(rawBody) : null;
      } catch {
        throw new BffError(
          "UPSTREAM_MALFORMED_RESPONSE",
          502,
          "The AccountShield service returned an invalid response.",
        );
      }

      if (
        !payload ||
        typeof payload !== "object" ||
        typeof (payload as Record<string, unknown>).status !== "string"
      ) {
        throw new BffError(
          "UPSTREAM_MALFORMED_RESPONSE",
          502,
          "The AccountShield service returned an invalid response.",
        );
      }

      return {
        status: (payload as Record<string, string>).status,
      };
    } catch (error) {
      if (error instanceof BffError) {
        throw error;
      }
      if (timedOut || controller.signal.aborted) {
        throw new BffError(
          "UPSTREAM_TIMEOUT",
          504,
          "The AccountShield service did not respond in time.",
          true,
          { cause: error },
        );
      }
      throw new BffError(
        "UPSTREAM_UNAVAILABLE",
        503,
        "The AccountShield service is unavailable.",
        true,
        { cause: error },
      );
    } finally {
      clearTimeout(timeout);
      callerSignal?.removeEventListener("abort", abortFromCaller);
    }
  }
}

export interface RuntimeStatusServiceOptions {
  source: RuntimeStatusSource;
  client?: AccountShieldReadClient;
  now?: () => Date;
}

export class RuntimeStatusService {
  private readonly source: RuntimeStatusSource;
  private readonly client?: AccountShieldReadClient;
  private readonly now: () => Date;

  constructor(options: RuntimeStatusServiceOptions) {
    this.source = options.source;
    this.client = options.client;
    this.now = options.now ?? (() => new Date());
  }

  async getStatus(
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<RuntimeStatusView> {
    let availability: RuntimeAvailability = "available";

    if (this.source === "live") {
      if (!this.client) {
        throw new BffError(
          "INTERNAL_ERROR",
          500,
          "The request could not be completed.",
        );
      }
      const health = await this.client.getRuntimeHealth(correlationId, signal);
      availability = health.status === "UP" ? "available" : "degraded";
    }

    return {
      availability,
      source: this.source,
      checkedAt: this.now().toISOString(),
      correlationId,
    };
  }
}
