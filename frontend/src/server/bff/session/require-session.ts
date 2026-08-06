// Shared guard for BFF routes that need an authenticated operator session. Nothing calls this
// yet in this PR -- existing feature routes (decision-search.ts and siblings) still read
// ACCOUNTSHIELD_OPERATOR_TOKEN directly and are wired to this guard in a follow-up PR. Built and
// tested now so that follow-up is a small, mechanical change per route.
//
// Deliberately does not import the literal "server-only" npm package, for the same reason as
// session.ts: the real boundary (ARCH001) is enforced by this file's path under src/server/,
// and omitting the import keeps this file directly testable under vitest's jsdom environment.
import { readFrontendEnvironment } from "@/config/environment";

import { BffError } from "../foundation";
import { isCsrfTokenValid, isTrustedOrigin } from "./csrf";
import { allowEnvTokenFallback, parseCookieHeader, resolveSessionCookieConfig } from "./session-core";
import { verifySessionCookieValue } from "./session-crypto";
import { getSession, touchSession } from "./session-store";

export interface AuthorizedSession {
  subject: string;
  roles: readonly string[];
  backendToken: string;
}

const SAFE_METHODS = new Set(["GET", "HEAD"]);

/**
 * Resolves the authenticated operator session for a BFF request, enforcing CSRF/origin
 * validation for state-changing methods. Throws a 401 BffError when no valid session exists and
 * a 403 BffError when CSRF/origin validation fails on a mutating request. Backend role checks
 * remain authoritative -- this only proves a valid operator session exists, it never replaces
 * the backend's own authorization decision.
 */
export function requireOperatorSession(
  request: Request,
  source: Readonly<Record<string, string | undefined>> = process.env,
): AuthorizedSession {
  const environment = readFrontendEnvironment(source, "runtime");
  const config = resolveSessionCookieConfig(source, environment.productionLike);

  const cookies = parseCookieHeader(request.headers.get("cookie"));
  const cookieValue = cookies[config.cookieName];
  const sessionId = cookieValue ? verifySessionCookieValue(cookieValue, config.sessionSecret) : undefined;
  if (!sessionId) {
    throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  }

  const lookup = getSession(sessionId, Date.now(), config);
  if (lookup.state !== "valid") {
    throw new BffError("UNAUTHORIZED", 401, "Operator authentication is required.");
  }

  const method = request.method.toUpperCase();
  if (!SAFE_METHODS.has(method)) {
    if (!isTrustedOrigin(request, new URL(request.url).origin)) {
      throw new BffError("FORBIDDEN", 403, "The request origin is not trusted.");
    }
    if (!isCsrfTokenValid(request, sessionId, lookup.record.csrfSecret, config.sessionSecret)) {
      throw new BffError("FORBIDDEN", 403, "CSRF validation failed.");
    }
  }

  touchSession(sessionId, Date.now());
  return { subject: lookup.record.subject, roles: lookup.record.roles, backendToken: lookup.record.backendToken };
}

/** Whether a route may still fall back to ACCOUNTSHIELD_OPERATOR_TOKEN when no session exists --
 * fixtures/local-dev convenience only, forced off in any productionLike environment. */
export function canFallBackToEnvToken(source: Readonly<Record<string, string | undefined>> = process.env): boolean {
  const environment = readFrontendEnvironment(source, "runtime");
  return allowEnvTokenFallback(source, environment.productionLike);
}

/**
 * Resolves the backend Bearer token a live BFF client should use: the authenticated session's
 * stored token when one exists, otherwise ACCOUNTSHIELD_OPERATOR_TOKEN when explicitly allowed
 * (see canFallBackToEnvToken). Backend role checks remain authoritative either way -- this only
 * decides whether the BFF attempts the upstream call at all.
 */
export function resolveOperatorToken(
  request: Request,
  source: Readonly<Record<string, string | undefined>> = process.env,
): string {
  try {
    return requireOperatorSession(request, source).backendToken;
  } catch (error) {
    if (error instanceof BffError && error.code === "UNAUTHORIZED" && canFallBackToEnvToken(source)) {
      const operatorToken = source.ACCOUNTSHIELD_OPERATOR_TOKEN?.trim();
      if (operatorToken) return operatorToken;
    }
    throw error;
  }
}
