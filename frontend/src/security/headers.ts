import type { AppEnvironment } from "@/config/environment";

export interface SecurityHeader {
  key: string;
  value: string;
}

interface ContentSecurityPolicyOptions {
  nonce: string;
  development: boolean;
  productionLike: boolean;
}

export function createContentSecurityPolicy({
  nonce,
  development,
  productionLike,
}: ContentSecurityPolicyOptions): string {
  if (!/^[A-Za-z0-9+/=_-]+$/.test(nonce)) {
    throw new Error("CSP nonce contains unsupported characters.");
  }

  const directives = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${development ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "font-src 'self'",
    `connect-src 'self'${development ? " ws: wss:" : ""}`,
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "manifest-src 'self'",
    "worker-src 'self' blob:",
  ];

  if (productionLike) {
    directives.push("upgrade-insecure-requests");
  }

  return `${directives.join("; ")};`;
}

export function createStaticSecurityHeaders(
  appEnvironment: AppEnvironment,
): SecurityHeader[] {
  const headers: SecurityHeader[] = [
    { key: "Cache-Control", value: "private, no-store, max-age=0, must-revalidate" },
    { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
    { key: "Cross-Origin-Resource-Policy", value: "same-origin" },
    { key: "Origin-Agent-Cluster", value: "?1" },
    {
      key: "Permissions-Policy",
      value:
        "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()",
    },
    { key: "Referrer-Policy", value: "no-referrer" },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-DNS-Prefetch-Control", value: "off" },
    { key: "X-Frame-Options", value: "DENY" },
    { key: "X-Robots-Tag", value: "noindex, nofollow, noarchive" },
  ];

  if (appEnvironment === "preview") {
    headers.push({
      key: "Strict-Transport-Security",
      value: "max-age=31536000",
    });
  }

  if (appEnvironment === "production") {
    headers.push({
      key: "Strict-Transport-Security",
      value: "max-age=63072000; includeSubDomains; preload",
    });
  }

  return headers;
}
