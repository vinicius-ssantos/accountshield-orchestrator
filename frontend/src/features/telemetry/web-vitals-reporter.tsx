"use client";

import { useReportWebVitals } from "next/web-vitals";

import {
  classifyTelemetryRoute,
  normalizeWebVital,
} from "./web-vitals-core";

const ENDPOINT = "/api/telemetry/web-vitals";

function normalizeNavigationType(value: string | undefined): string {
  if (value === "back-forward-cache") return "restore";
  return value ?? "unknown";
}

function sendMetric(body: string): void {
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

export function WebVitalsReporter() {
  useReportWebVitals((metric) => {
    const safeMetric = normalizeWebVital({
      name: metric.name,
      value: metric.value,
      rating: metric.rating,
      navigationType: normalizeNavigationType(metric.navigationType),
      route: classifyTelemetryRoute(window.location.pathname),
    });

    if (!safeMetric) return;
    sendMetric(JSON.stringify(safeMetric));
  });

  return null;
}
