import type { SafeWebVital } from "./web-vitals-core";

const ENDPOINT = "/api/bff/telemetry/web-vitals";

export function sendWebVital(metric: SafeWebVital): void {
  const body = JSON.stringify(metric);
  const blob = new Blob([body], { type: "application/json" });
  if (navigator.sendBeacon?.(ENDPOINT, blob)) return;

  void fetch(ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body,
    cache: "no-store",
    credentials: "same-origin",
    keepalive: true,
  }).catch(() => undefined);
}
