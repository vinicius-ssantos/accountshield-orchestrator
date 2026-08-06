import { BffError } from "./foundation";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface RequeueInput {
  eventId: string;
}

export interface RequeueResult {
  requeued: true;
}

export interface OutboxRequeueService {
  requeue(input: RequeueInput, correlationId: string, signal?: AbortSignal): Promise<RequeueResult>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function assertEventId(value: unknown): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new BffError("INVALID_REQUEST", 400, "eventId must be a valid identifier.");
  }
  return value;
}

export function parseRequeueInput(body: Record<string, unknown>): RequeueInput {
  return { eventId: assertEventId(body.eventId) };
}

export interface OutboxRequeueClientConfig {
  origin: string;
  operatorToken: string;
  timeoutMs: number;
}

// No new step-up-disclosure vocabulary needed here (unlike the three prior mutations): requeue is
// operational remediation, not a privileged security action, so the backend gates it on role alone
// and never issues a challenge. OUTBOX_EVENT_NOT_DEAD_LETTERED is rollout/lifecycle's
// ROLLOUT_ALREADY_ACTIVE/SELF_APPROVAL_NOT_ALLOWED pattern applied to this module's own, distinct
// conflict: an event exists but isn't currently in a requeueable state.
function mapUpstreamFailure(status: number, body: Record<string, unknown>): never {
  const upstreamCode = typeof body.code === "string" ? body.code : undefined;
  if (upstreamCode === "OUTBOX_EVENT_NOT_DEAD_LETTERED") {
    throw new BffError(
      "OUTBOX_EVENT_NOT_DEAD_LETTERED",
      status,
      "This outbox event is no longer dead-lettered and cannot be requeued.",
    );
  }
  if (upstreamCode === "OUTBOX_EVENT_NOT_FOUND") {
    throw new BffError("NOT_FOUND", status, "The outbox event was not found.");
  }
  if (status === 401) throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  if (status === 403) throw new BffError("FORBIDDEN", 403, "Outbox requeue is not permitted.");
  if (status === 404) throw new BffError("NOT_FOUND", 404, "The outbox event was not found.");
  if (status === 409) throw new BffError("CONFLICT", 409, "The outbox event state has since changed.");
  throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Outbox requeue is temporarily unavailable.", true);
}

export class AccountShieldOutboxRequeueClient implements OutboxRequeueService {
  constructor(private readonly config: OutboxRequeueClientConfig) {}

  async requeue(input: RequeueInput, correlationId: string, signal?: AbortSignal): Promise<RequeueResult> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);
    if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true });
    try {
      const response = await fetch(`${this.config.origin}/api/v1/outbox/${input.eventId}/requeue`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${this.config.operatorToken}`,
          "x-correlation-id": correlationId,
        },
        signal: controller.signal,
      });
      if (response.status === 204) return { requeued: true };

      const contentType = response.headers.get("content-type")?.split(";", 1)[0]?.trim();
      let body: unknown = {};
      if (contentType === "application/json" || contentType === "application/problem+json") {
        try {
          body = await response.json();
        } catch (error) {
          throw new BffError("UPSTREAM_MALFORMED_RESPONSE", 502, "The upstream response is invalid.", false, {
            cause: error,
          });
        }
      }
      mapUpstreamFailure(response.status, isRecord(body) ? body : {});
    } catch (error) {
      if (error instanceof BffError) throw error;
      if (controller.signal.aborted) {
        throw new BffError("UPSTREAM_TIMEOUT", 504, "Outbox requeue timed out.", true, { cause: error });
      }
      throw new BffError("UPSTREAM_UNAVAILABLE", 502, "Outbox requeue is temporarily unavailable.", true, {
        cause: error,
      });
    } finally {
      clearTimeout(timeout);
    }
  }
}
