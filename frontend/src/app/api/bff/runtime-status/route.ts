import { handleRuntimeStatusRequest } from "@/server/bff/runtime-status";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  return handleRuntimeStatusRequest(request);
}

export async function POST(request: Request): Promise<Response> {
  return handleRuntimeStatusRequest(request);
}

export async function PUT(request: Request): Promise<Response> {
  return handleRuntimeStatusRequest(request);
}

export async function PATCH(request: Request): Promise<Response> {
  return handleRuntimeStatusRequest(request);
}

export async function DELETE(request: Request): Promise<Response> {
  return handleRuntimeStatusRequest(request);
}
