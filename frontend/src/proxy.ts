import { NextResponse, type NextRequest } from "next/server";

import { readFrontendEnvironment } from "./config/environment";
import { createContentSecurityPolicy } from "./security/headers";

const SENSITIVE_CACHE_POLICY =
  "private, no-store, max-age=0, must-revalidate";

export function proxy(request: NextRequest): NextResponse {
  const environment = readFrontendEnvironment(process.env, "runtime");
  const nonce = crypto.randomUUID().replaceAll("-", "");
  const contentSecurityPolicy = createContentSecurityPolicy({
    nonce,
    development: process.env.NODE_ENV === "development",
    productionLike: environment.productionLike,
  });

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("Content-Security-Policy", contentSecurityPolicy);
  requestHeaders.set("x-nonce", nonce);

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
