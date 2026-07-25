export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

export function GET(): Response {
  return Response.json(
    { status: "ok" },
    {
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
