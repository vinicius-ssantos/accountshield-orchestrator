import { NextResponse, type NextRequest } from "next/server";

import { readFrontendEnvironment } from "./config/environment";
import { createContentSecurityPolicy } from "./security/headers";
import { isTrustedOrigin } from "./server/bff/session/csrf";
import { parseCookieHeader, resolveSessionCookieConfig } from "./server/bff/session/session-core";
import { verifySessionCookieValue } from "./server/bff/session/session-crypto";
import { getSession } from "./server/bff/session/session-store";

// No explicit runtime export: Next.js 16's proxy.ts always runs on the Node.js runtime (unlike
// the old middleware.ts, which defaulted to Edge) and rejects a route segment config here, so
// node:crypto (used transitively via the session module) is already guaranteed to be available.

const SENSITIVE_CACHE_POLICY =
  "private, no-store, max-age=0, must-revalidate";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

type OperatorSessionState = "valid" | "expired" | "absent" | "untrusted-origin";

function resolveSessionState(request: NextRequest, productionLike: boolean): OperatorSessionState {
  const method = request.method.toUpperCase();
  if (!SAFE_METHODS.has(method) && !isTrustedOrigin(request, request.nextUrl.origin)) {
    return "untrusted-origin";
  }

  const source = process.env;
  const config = resolveSessionCookieConfig(source, productionLike);
  const cookies = parseCookieHeader(request.headers.get("cookie"));
  const cookieValue = cookies[config.cookieName];
  const sessionId = cookieValue ? verifySessionCookieValue(cookieValue, config.sessionSecret) : undefined;
  if (!sessionId) return "absent";

  const lookup = getSession(sessionId, Date.now(), config);
  return lookup.state;
}

export function proxy(request: NextRequest): NextResponse {
  const environment = readFrontendEnvironment(process.env, "runtime");
  const nonce = crypto.randomUUID().replaceAll("-", "");
  const contentSecurityPolicy = createContentSecurityPolicy({
    nonce,
    development: process.env.NODE_ENV === "development",
    productionLike: environment.productionLike,
  });
  const sessionState = resolveSessionState(request, environment.productionLike);

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("Content-Security-Policy", contentSecurityPolicy);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("x-operator-session-state", sessionState);

  const response = NextResponse.next({
    request: {
      headers: requestHeaders,
    },
  });
  response.headers.set("Content-Security-Policy", contentSecurityPolicy);
  response.headers.set("Cache-Control", SENSITIVE_CACHE_POLICY);

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
