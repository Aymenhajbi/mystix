import { fetchLogPayload } from "@/lib/api";

/** Request body kept with a rejected submission, shown as JSON text in the browser. */
export async function GET(_request: Request, ctx: RouteContext<"/api/logs/[id]/payload">) {
  const { id } = await ctx.params;
  const result = await fetchLogPayload(id);
  if (result.kind === "ok") {
    return new Response(result.data, {
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Disposition": `inline; filename="request-${id.replace(/[^0-9a-f-]/gi, "")}.json"`,
        "X-Content-Type-Options": "nosniff",
      },
    });
  }
  return new Response(null, { status: result.kind === "unreachable" ? 502 : 404 });
}
