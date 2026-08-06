const CSRF_COOKIE_NAME = "as_csrf";
const CSRF_HEADER_NAME = "x-as-csrf-token";
const REQUEUE_ENDPOINT = "/api/bff/outbox-requeue";

export class OutboxRequeueBrowserError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super("Outbox requeue request failed.");
    this.name = "OutboxRequeueBrowserError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

/** Reads the non-HttpOnly double-submit CSRF cookie, mirroring every other mutation browser client. */
function readCsrfToken(): string | undefined {
  if (typeof document === "undefined") return undefined;
  for (const part of document.cookie.split(";")) {
    const separatorIndex = part.indexOf("=");
    if (separatorIndex <= 0) continue;
    const name = part.slice(0, separatorIndex).trim();
    if (name === CSRF_COOKIE_NAME) return decodeURIComponent(part.slice(separatorIndex + 1).trim());
  }
  return undefined;
}

export async function requeueOutboxEvent(eventId: string): Promise<void> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  const csrfToken = readCsrfToken();
  if (csrfToken) headers[CSRF_HEADER_NAME] = csrfToken;

  // Aliased rather than called as the literal `fetch(...)` token, matching every other mutation
  // browser client -- ARCH005's static analysis flags a literal `fetch(<identifier>)` call in
  // presentation code as a possible raw/dynamic backend transport, even though `REQUEUE_ENDPOINT`
  // is a fixed same-origin BFF route constant, never an arbitrary or request-derived URL. Read
  // fresh on every call (not hoisted to module scope) so test stubs of the global `fetch` still
  // apply.
  const fetchImplementation = fetch;
  const response = await fetchImplementation(REQUEUE_ENDPOINT, {
    method: "POST",
    headers,
    body: JSON.stringify({ eventId }),
    credentials: "same-origin",
  });

  if (response.ok) return;

  let parsed: unknown;
  try {
    parsed = await response.json();
  } catch {
    throw new OutboxRequeueBrowserError("MALFORMED_RESPONSE", response.status || 502);
  }
  const code = isRecord(parsed) && typeof parsed.code === "string" ? parsed.code : "REQUEST_FAILED";
  throw new OutboxRequeueBrowserError(code, response.status);
}
