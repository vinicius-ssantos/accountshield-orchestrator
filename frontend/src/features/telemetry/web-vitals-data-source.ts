import type { SafeWebVital } from "./web-vitals-core";

const ENDPOINT = "/api/bff/telemetry/web-vitals";

export function sendWebVital(metric: SafeWebVital): void {
  if (typeof navigator.sendBeacon !== "function") return;

  const body = JSON.stringify(metric);
  const blob = new Blob([body], { type: "application/json" });
  navigator.sendBeacon(ENDPOINT, blob);
}
