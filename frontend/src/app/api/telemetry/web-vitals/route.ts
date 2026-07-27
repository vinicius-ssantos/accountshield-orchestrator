const REPLACEMENT_ENDPOINT = "/api/bff/telemetry/web-vitals";

export async function POST(): Promise<Response> {
  return Response.json(
    {
      code: "ENDPOINT_RETIRED",
      replacement: REPLACEMENT_ENDPOINT,
    },
    {
      status: 410,
      headers: {
        "cache-control": "private, no-store, max-age=0, must-revalidate",
      },
    },
  );
}
