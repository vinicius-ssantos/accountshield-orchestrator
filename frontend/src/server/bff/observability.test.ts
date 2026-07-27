import { describe, expect, it } from "vitest";

import { BffError } from "./foundation";
import { InMemoryBffTelemetrySink, startBffTelemetry } from "./observability";

function clock(...values: number[]): () => number {
  let index = 0;
  return () => values[Math.min(index++, values.length - 1)] ?? 0;
}

describe("BFF observability", () => {
  it("records one successful bounded-cardinality event", () => {
    const sink = new InMemoryBffTelemetrySink();
    const operation = startBffTelemetry({
      useCase: "runtime_status",
      correlationId: "corr_12345678",
      sink,
      now: clock(100, 143),
    });

    operation.succeed(200);
    operation.succeed(200);

    expect(sink.events).toEqual([
      {
        event: "accountshield.bff.request.completed",
        useCase: "runtime_status",
        outcome: "success",
        origin: "route",
        statusClass: "2xx",
        retryable: false,
        durationMs: 43,
        correlationId: "corr_12345678",
        diagnosticCode: "OK",
      },
    ]);
  });

  it.each([
    [new BffError("METHOD_NOT_ALLOWED", 405, "ignored"), "invalid_request", "route", "4xx", false],
    [new BffError("FORBIDDEN", 403, "ignored"), "denied", "spring_api", "4xx", false],
    [new BffError("UPSTREAM_TIMEOUT", 504, "ignored", true), "timeout", "transport", "5xx", true],
    [new BffError("REQUEST_CANCELLED", 499, "ignored"), "cancelled", "transport", "4xx", false],
    [new BffError("UPSTREAM_UNAVAILABLE", 503, "ignored", true), "upstream_unavailable", "transport", "5xx", true],
    [new BffError("INVALID_UPSTREAM_RESPONSE", 502, "ignored"), "malformed_response", "adapter", "5xx", false],
  ] as const)("classifies %s without raw error detail", (error, outcome, origin, statusClass, retryable) => {
    const sink = new InMemoryBffTelemetrySink();
    const operation = startBffTelemetry({
      useCase: "runtime_status",
      correlationId: "corr_12345678",
      sink,
      now: clock(1, 2),
    });

    operation.fail(error);

    expect(sink.events[0]).toMatchObject({ outcome, origin, statusClass, retryable });
    expect(JSON.stringify(sink.events[0])).not.toContain("ignored");
  });

  it("does not leak arbitrary exception messages", () => {
    const sink = new InMemoryBffTelemetrySink();
    const operation = startBffTelemetry({
      useCase: "runtime_status",
      correlationId: "corr_12345678",
      sink,
      now: clock(5, 8),
    });

    operation.fail(new Error("sensitive account-123"));

    expect(sink.events[0]).toEqual({
      event: "accountshield.bff.request.completed",
      useCase: "runtime_status",
      outcome: "internal_error",
      origin: "unknown",
      statusClass: "none",
      retryable: false,
      durationMs: 3,
      correlationId: "corr_12345678",
      diagnosticCode: "INTERNAL_ERROR",
    });
  });
});
