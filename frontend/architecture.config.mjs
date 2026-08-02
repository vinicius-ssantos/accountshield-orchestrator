const architectureConfig = {
  publicEnvAllowlist: ["NEXT_PUBLIC_APP_ENV"],
  generatedImportAllowedPrefixes: ["src/server/bff/"],
  // src/features/recoveries/ is deliberately NOT listed here as of issue #194: it gained its
  // first real mutation (operator recovery review, gated by require-session.ts's CSRF/origin
  // enforcement, no env-token fallback). See ADR 0018.
  // src/features/policies/ is deliberately NOT listed here as of issue #197, for the same
  // reason: policy lifecycle approve/activate/reject/retire mutations, gated the same way.
  // src/features/outbox/ is deliberately NOT listed here as of issue #203: dead-letter requeue,
  // the first console mutation with no step-up gate (operational remediation, not a privileged
  // security action -- see ADR 0020).
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
    "src/features/decisions/",
  ],
  exceptions: [
    {
      ruleId: "ARCH007",
      path: "src/features/decisions/decision-search-browser.ts",
      rationale:
        "Decision search is read-only but sends the validated correlation ID and filters in a same-origin POST body instead of a query string, so sensitive identifiers are never placed in the URL.",
      owner: "vinicius-ssantos",
      issue: "#69",
      revisitOn: "2026-10-29",
    },
    {
      ruleId: "ARCH007",
      path: "src/features/decisions/decision-timeline-browser.ts",
      rationale:
        "Decision investigation is read-only but sends the opaque decision reference in a same-origin POST body so it never appears in the browser URL, history, referrer, or access logs.",
      owner: "vinicius-ssantos",
      issue: "#70",
      revisitOn: "2026-10-29",
    },
    {
      ruleId: "ARCH007",
      path: "src/features/decisions/decision-replay-browser.ts",
      rationale:
        "Decision replay is read-only and side-effect-free but sends the opaque decision reference in a same-origin POST body so it never appears in the browser URL, history, referrer, or access logs.",
      owner: "vinicius-ssantos",
      issue: "#72",
      revisitOn: "2026-10-29",
    },
  ],
};

export default architectureConfig;
