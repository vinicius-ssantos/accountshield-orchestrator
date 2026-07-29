import { handleDecisionSearchRequest } from "@/server/bff/decision-search";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

export async function POST(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request);
}

export async function GET(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request);
}

export async function PUT(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request);
}

export async function PATCH(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request);
}

export async function DELETE(request: Request): Promise<Response> {
  return handleDecisionSearchRequest(request);
}
