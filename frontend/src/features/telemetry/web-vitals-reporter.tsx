"use client";

import { useReportWebVitals } from "next/web-vitals";

import {
  classifyTelemetryRoute,
  normalizeWebVital,
} from "./web-vitals-core";
import { sendWebVital } from "./web-vitals-data-source";

function normalizeNavigationType(value: string | undefined): string {
  if (value === "back-forward-cache") return "restore";
  return value ?? "unknown";
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

    if (safeMetric) sendWebVital(safeMetric);
  });

  return null;
}
