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
  // src/features/decisions/ is deliberately NOT listed here as of issue #214: evidence export,
  // its first real mutation (audited, no step-up -- see ADR 0021). The ARCH007 exceptions below
  // for decision-search-browser.ts/decision-timeline-browser.ts/decision-replay-browser.ts were
  // removed alongside this change: ARCH007 only fires for paths inside readOnlyScopes, so once
  // this directory left the list those entries became stale exceptions for a rule that no longer
  // applies to them.
  readOnlyScopes: [
    "src/app/api/bff/runtime-status/",
    "src/server/bff/runtime-status",
  ],
  exceptions: [],
};

export default architectureConfig;
