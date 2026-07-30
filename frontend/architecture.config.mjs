const architectureConfig = {
  publicEnvAllowlist: ["NEXT_PUBLIC_APP_ENV"],
  generatedImportAllowedPrefixes: ["src/server/bff/"],
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
    "src/features/decisions/",
    "src/features/recoveries/",
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
  ],
};

export default architectureConfig;
