import { handleSessionStatusRequest } from "@/server/bff/session/session";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  return handleSessionStatusRequest(request);
}
