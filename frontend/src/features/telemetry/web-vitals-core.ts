export const WEB_VITAL_NAMES = ["CLS", "FCP", "INP", "LCP", "TTFB"] as const;
export const WEB_VITAL_RATINGS = ["good", "needs-improvement", "poor"] as const;
export const NAVIGATION_TYPES = [
  "navigate",
  "reload",
  "back-forward",
  "prerender",
  "restore",
  "unknown",
] as const;
export const TELEMETRY_ROUTES = [
  "home",
  "decisions",
  "policies",
  "challenges",
  "recovery",
  "audit",
  "design-system",
  "unknown",
] as const;

type WebVitalName = (typeof WEB_VITAL_NAMES)[number];
type WebVitalRating = (typeof WEB_VITAL_RATINGS)[number];
type NavigationType = (typeof NAVIGATION_TYPES)[number];
export type TelemetryRoute = (typeof TELEMETRY_ROUTES)[number];

export interface SafeWebVital {
  readonly name: WebVitalName;
  readonly value: number;
  readonly rating: WebVitalRating;
  readonly navigationType: NavigationType;
  readonly route: TelemetryRoute;
}

function isAllowed<T extends readonly string[]>(values: T, value: unknown): value is T[number] {
  return typeof value === "string" && values.includes(value);
}

export function classifyTelemetryRoute(pathname: string): TelemetryRoute {
  const firstSegment = pathname.split("/").filter(Boolean)[0]?.toLowerCase();
  if (!firstSegment) return "home";
  if (isAllowed(TELEMETRY_ROUTES, firstSegment)) return firstSegment;
  return "unknown";
}

export function normalizeWebVital(input: unknown): SafeWebVital | null {
  if (!input || typeof input !== "object" || Array.isArray(input)) return null;

  const record = input as Record<string, unknown>;
  const allowedKeys = new Set(["name", "value", "rating", "navigationType", "route"]);
  if (Object.keys(record).some((key) => !allowedKeys.has(key))) return null;
  if (!isAllowed(WEB_VITAL_NAMES, record.name)) return null;
  if (!isAllowed(WEB_VITAL_RATINGS, record.rating)) return null;
  if (!isAllowed(NAVIGATION_TYPES, record.navigationType)) return null;
  if (!isAllowed(TELEMETRY_ROUTES, record.route)) return null;
  if (typeof record.value !== "number" || !Number.isFinite(record.value)) return null;
  if (record.value < 0 || record.value > 600_000) return null;

  return {
    name: record.name,
    value: Math.round(record.value * 1_000) / 1_000,
    rating: record.rating,
    navigationType: record.navigationType,
    route: record.route,
  };
}
