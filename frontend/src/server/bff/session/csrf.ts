// Double-submit-cookie CSRF, chosen over a synchronizer token (no extra per-form server state
// needed beyond the session record already being stored) and over relying on SameSite=Lax
// alone (defense in depth against any future same-site subdomain or browser-implementation
// edge case). Combined with the Origin/Sec-Fetch-Site check below.
import { signCsrfToken, verifyCsrfToken } from "./session-crypto";

export const CSRF_COOKIE_NAME = "as_csrf";
export const CSRF_HEADER_NAME = "x-as-csrf-token";

export function csrfCookieValue(sessionId: string, csrfSecret: string, secret: string): string {
  return signCsrfToken(sessionId, csrfSecret, secret);
}

/** A custom header (not a form field) additionally defeats plain cross-site <form> submissions,
 * which cannot set custom headers without CORS -- the BFF grants none. */
export function isCsrfTokenValid(
  request: Request,
  sessionId: string,
  csrfSecret: string,
  secret: string,
): boolean {
  const header = request.headers.get(CSRF_HEADER_NAME);
  if (!header) return false;
  return verifyCsrfToken(header, sessionId, csrfSecret, secret);
}

/** Origin/Sec-Fetch-Site validation for state-changing requests, ahead of the CSRF token check.
 * Sec-Fetch-Site is preferred when present (sent by all modern browsers); Origin is the
 * fallback for older clients. A request with neither header is treated as untrusted only when
 * an Origin was expected but omitted -- same-origin same-tab navigations can legitimately omit
 * both, so the CSRF token check remains the primary defense. */
export function isTrustedOrigin(request: Request, selfOrigin: string): boolean {
  const secFetchSite = request.headers.get("sec-fetch-site");
  if (secFetchSite) {
    return secFetchSite === "same-origin" || secFetchSite === "none";
  }
  const origin = request.headers.get("origin");
  if (!origin) return true;
  try {
    return new URL(origin).origin === selfOrigin;
  } catch {
    return false;
  }
}
