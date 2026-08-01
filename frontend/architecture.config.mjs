const architectureConfig = {
  publicEnvAllowlist: ["NEXT_PUBLIC_APP_ENV"],
  generatedImportAllowedPrefixes: ["src/server/bff/"],
  // src/features/recoveries/ is deliberately NOT listed here as of issue #194: it gained its
  // first real mutation (operator recovery review, gated by require-session.ts's CSRF/origin
  // enforcement, no env-token fallback). See ADR 0018.
  // src/features/policies/ is deliberately NOT listed here as of issue #197, for the same
  // reason: policy lifecycle approve/activate/reject/retire mutations, gated the same way.
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
    "src/features/decisions/",
    "src/features/outbox/",
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
    {
      ruleId: "ARCH007",
      path: "src/features/outbox/outbox-browser.ts",
      rationale:
        "Outbox search is read-only but sends status/event-type/time-window/attempt-count filters and the keyset cursor in a same-origin POST body instead of a query string, so operational identifiers are never placed in the URL.",
      owner: "vinicius-ssantos",
      issue: "#74",
      revisitOn: "2026-10-29",
    },
  ],
};

export default architectureConfig;
