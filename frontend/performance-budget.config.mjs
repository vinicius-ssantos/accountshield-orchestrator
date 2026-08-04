// Budget numbers backing `npm run budget:check` (scripts/check-bundle-budget.mjs). Reviewed
// against the actual `.next/diagnostics/route-bundle-stats.json` output at the time this budget
// was set: the heaviest route (/decisions) was ~577KB of uncompressed first-load JS, the lightest
// (/[section]) ~527KB. All routes share nearly the same shared-chunk floor (React, the design
// system, session/telemetry client code), so a single global ceiling -- not a per-route budget --
// is what actually catches an accidental heavy per-route import without needing per-route
// tuning every time a shared dependency changes size.
const performanceBudgetConfig = {
  // Uncompressed first-load JS, in bytes, per route. ~13% headroom above the heaviest route
  // observed when this budget was set -- tight enough to fail on an accidental heavy import
  // (e.g. a moment/lodash-style library pulled into a client component), loose enough to absorb
  // normal dependency bumps without needing a budget change on every PR.
  maxFirstLoadUncompressedJsBytes: 650 * 1024,
};

export default performanceBudgetConfig;
