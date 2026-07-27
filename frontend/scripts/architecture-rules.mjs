export const RULES = {
  ARCH001: ["Client component crosses a server-only boundary", "Move the import behind a Server Component, Route Handler, or browser-safe adapter."],
  ARCH002: ["Generated transport imported outside the BFF", "Consume generated contracts through a handwritten src/server/bff adapter."],
  ARCH003: ["Fixture bypasses the feature data-source boundary", "Resolve fixtures only inside the feature get-data-source composition module."],
  ARCH004: ["Browser environment variable is not allowlisted", "Keep server values unprefixed and change the public allowlist through ADR review."],
  ARCH005: ["Presentation code performs raw backend access", "Call a narrow /api/bff route or inject a feature data source."],
  ARCH006: ["Generic BFF proxy pattern detected", "Expose a named route with a fixed upstream operation."],
  ARCH007: ["Read-only scope contains an upstream mutation", "Remove the mutation or define authorization, idempotency, confirmation, and audit contracts."],
  ARCH008: ["Forbidden frontend dependency direction", "Depend inward through feature contracts, server adapters, and shared UI."],
  ARCH009: ["Forbidden cross-feature import", "Extract a shared contract or compose features at the app layer."],
  ARCH010: ["Frontend dependency cycle detected", "Extract a one-way contract or move composition to a higher layer."],
  ARCH011: ["Architecture exception policy is invalid", "Use exact paths, an owner, issue, rationale, and future revisit date."],
};

export function violation(ruleId, file, line, message, target = null) {
  return { ruleId, file, line, target, message, title: RULES[ruleId][0], remediation: RULES[ruleId][1] };
}

export function validateExceptions(config, today) {
  const violations = [];
  const valid = [];
  for (const [index, exception] of (config.exceptions ?? []).entries()) {
    const label = `architecture.config.mjs exception[${index}]`;
    const required = ["ruleId", "path", "rationale", "owner", "issue", "revisitOn"];
    const missing = required.filter((key) => !exception?.[key]);
    const wildcard = /[*?]/.test(exception?.path ?? "") || /[*?]/.test(exception?.target ?? "");
    const dateValid = /^\d{4}-\d{2}-\d{2}$/.test(exception?.revisitOn ?? "");
    const expired = dateValid && exception.revisitOn < today;
    const ruleValid = Boolean(RULES[exception?.ruleId]) && exception.ruleId !== "ARCH011";
    const rationaleValid = (exception?.rationale?.trim().length ?? 0) >= 20;
    const issueValid = /^(?:#\d+|https:\/\/github\.com\/)/.test(exception?.issue ?? "");
    if (missing.length || wildcard || !dateValid || expired || !ruleValid || !rationaleValid || !issueValid) {
      const reasons = [
        missing.length ? `missing ${missing.join(", ")}` : null,
        wildcard ? "wildcards are forbidden" : null,
        !dateValid ? "revisitOn must be YYYY-MM-DD" : null,
        expired ? `revisitOn ${exception.revisitOn} is expired` : null,
        !ruleValid ? "ruleId is unknown or cannot exempt ARCH011" : null,
        !rationaleValid ? "rationale must contain at least 20 characters" : null,
        !issueValid ? "issue must be #<number> or a GitHub URL" : null,
      ].filter(Boolean);
      violations.push(violation("ARCH011", label, 1, reasons.join("; ")));
    } else {
      valid.push({ ...exception, matched: false });
    }
  }
  return { violations, valid };
}

export function applyExceptions(violations, exceptions) {
  const remaining = [];
  for (const current of violations) {
    const exception = exceptions.find((candidate) =>
      candidate.ruleId === current.ruleId && candidate.path === current.file &&
      (candidate.target === undefined || candidate.target === current.target));
    if (exception) exception.matched = true;
    else remaining.push(current);
  }
  for (const exception of exceptions) {
    if (!exception.matched) remaining.push(violation(
      "ARCH011", "architecture.config.mjs", 1,
      `stale exception for ${exception.ruleId} at ${exception.path} did not match a current violation`,
      exception.target ?? null));
  }
  return remaining;
}
