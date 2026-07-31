const architectureConfig = {
  publicEnvAllowlist: ["NEXT_PUBLIC_APP_ENV"],
  generatedImportAllowedPrefixes: ["src/server/bff/"],
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
    "src/features/decisions/",
    "src/features/recoveries/",
    "src/features/policies/",
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
      path: "src/features/recoveries/recovery-search-browser.ts",
      rationale:
        "Recovery search is read-only but sends the validated filters in a same-origin POST body instead of a query string, so operational references are never placed in the URL.",
      owner: "vinicius-ssantos",
      issue: "#71",
      revisitOn: "2026-10-29",
    },
    {
      ruleId: "ARCH007",
      path: "src/features/recoveries/recovery-detail-browser.ts",
      rationale:
        "Recovery investigation is read-only but sends the opaque recovery reference in a same-origin POST body so it never appears in the browser URL, history, referrer, or access logs.",
      owner: "vinicius-ssantos",
      issue: "#71",
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
      path: "src/features/policies/policy-directory-browser.ts",
      rationale:
        "Policy directory search is read-only but uses a same-origin POST body for consistency with the other operator read surfaces, even though it currently sends no filters.",
      owner: "vinicius-ssantos",
      issue: "#73",
      revisitOn: "2026-10-29",
    },
    {
      ruleId: "ARCH007",
      path: "src/features/policies/policy-investigation-browser.ts",
      rationale:
        "Policy investigation is read-only but sends the policy key in a same-origin POST body instead of a query string, so it never appears in the browser URL, history, referrer, or access logs.",
      owner: "vinicius-ssantos",
      issue: "#73",
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
