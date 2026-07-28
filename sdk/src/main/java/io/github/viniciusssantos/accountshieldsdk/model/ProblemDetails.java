package io.github.viniciusssantos.accountshieldsdk.model;

import java.util.Map;

/**
 * Typed RFC 9457 Problem Details. Every AccountShield error response is a standard Spring
 * {@code ProblemDetail} (type/title/status/detail/instance) plus a stable {@code code} extension
 * property every handler sets, plus zero or more handler-specific extension properties (e.g.
 * {@code retryAfter}, {@code observedAt}) that vary by error type -- those land in
 * {@link #extensions()} rather than being modeled individually, since there is no single fixed set
 * across every problem type this API returns. Parsed by
 * {@link io.github.viniciusssantos.accountshieldsdk.internal.ProblemDetailsParser}, not via Jackson
 * annotations directly on this record, since Jackson's record/builder deserialization does not
 * cleanly support "known fields typed, everything else into a catch-all map" without a hand-written
 * parse step.
 */
public record ProblemDetails(
        String type, String title, Integer status, String detail, String instance, String code,
        Map<String, Object> extensions) {

    public ProblemDetails {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
