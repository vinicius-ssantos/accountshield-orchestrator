import { BffError, createSafeLogRecord } from "./foundation";

export type BffOutcome =
  | "success"
  | "invalid_request"
  | "denied"
  | "timeout"
  | "cancelled"
  | "upstream_unavailable"
  | "malformed_response"
  | "internal_error";

export type BffFailureOrigin =
  | "route"
  | "transport"
  | "adapter"
  | "spring_api"
  | "unknown";

export interface BffTelemetryEvent {
  readonly event: "accountshield.bff.request.completed";
  readonly useCase: string;
  readonly outcome: BffOutcome;
  readonly origin: BffFailureOrigin;
  readonly statusClass: "2xx" | "4xx" | "5xx" | "none";
  readonly retryable: boolean;
  readonly durationMs: number;
  readonly correlationId: string;
  readonly diagnosticCode: string;
}

export interface BffTelemetrySink {
  record(event: BffTelemetryEvent): void;
}

export class ConsoleBffTelemetrySink implements BffTelemetrySink {
  record(event: BffTelemetryEvent): void {
    const context: Record<string, unknown> = { ...event };
    console.info(JSON.stringify(createSafeLogRecord(event.event, event.correlationId, context)));
  }
}

export class InMemoryBffTelemetrySink implements BffTelemetrySink {
  readonly events: BffTelemetryEvent[] = [];

  record(event: BffTelemetryEvent): void {
    this.events.push(structuredClone(event));
  }
}

export interface BffTelemetryOperation {
  succeed(status?: number): void;
  fail(error: unknown, status?: number): void;
  cancel(status?: number): void;
}

function statusClass(status: number | undefined): BffTelemetryEvent["statusClass"] {
  if (!status) return "none";
  if (status >= 200 && status < 300) return "2xx";
  if (status >= 400 && status < 500) return "4xx";
  if (status >= 500 && status < 600) return "5xx";
  return "none";
}

function classify(error: unknown): {
  outcome: BffOutcome;
  origin: BffFailureOrigin;
  code: string;
  retryable: boolean;
} {
  if (error instanceof BffError) {
    const code = error.code;
    if (["METHOD_NOT_ALLOWED", "INVALID_REQUEST", "UNSUPPORTED_MEDIA_TYPE", "PAYLOAD_TOO_LARGE"].includes(code)) {
      return { outcome: "invalid_request", origin: "route", code, retryable: false };
    }
    if (["FORBIDDEN", "UNAUTHORIZED"].includes(code)) {
      return { outcome: "denied", origin: "spring_api", code, retryable: false };
    }
    if (code === "UPSTREAM_TIMEOUT") {
      return { outcome: "timeout", origin: "transport", code, retryable: true };
    }
    if (code === "UPSTREAM_UNAVAILABLE") {
      return { outcome: "upstream_unavailable", origin: "transport", code, retryable: true };
    }
    if (code === "UPSTREAM_MALFORMED_RESPONSE") {
      return { outcome: "malformed_response", origin: "adapter", code, retryable: false };
    }
    return { outcome: "internal_error", origin: "unknown", code, retryable: error.retryable };
  }

  return { outcome: "internal_error", origin: "unknown", code: "INTERNAL_ERROR", retryable: false };
}

export function startBffTelemetry(input: {
  readonly useCase: string;
  readonly correlationId: string;
  readonly sink?: BffTelemetrySink;
  readonly now?: () => number;
}): BffTelemetryOperation {
  const sink = input.sink ?? new ConsoleBffTelemetrySink();
  const now = input.now ?? (() => performance.now());
  const startedAt = now();
  let completed = false;

  function emit(event: Omit<BffTelemetryEvent, "event" | "durationMs" | "correlationId" | "useCase">): void {
    if (completed) return;
    completed = true;
    sink.record({
      event: "accountshield.bff.request.completed",
      useCase: input.useCase,
      correlationId: input.correlationId,
      durationMs: Math.max(0, Math.round(now() - startedAt)),
      ...event,
    });
  }

  return {
    succeed(status = 200): void {
      emit({
        outcome: "success",
        origin: "route",
        statusClass: statusClass(status),
        retryable: false,
        diagnosticCode: "OK",
      });
    },
    fail(error: unknown, status?: number): void {
      const classified = classify(error);
      emit({
        outcome: classified.outcome,
        origin: classified.origin,
        statusClass: statusClass(status ?? (error instanceof BffError ? error.status : undefined)),
        retryable: classified.retryable,
        diagnosticCode: classified.code,
      });
    },
    cancel(status = 499): void {
      emit({
        outcome: "cancelled",
        origin: "transport",
        statusClass: statusClass(status),
        retryable: false,
        diagnosticCode: "REQUEST_CANCELLED",
      });
    },
  };
}
