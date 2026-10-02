import { fetchInvoiceUbl } from "@/lib/api";

/** Streams the stored UBL 2.1 of an invoice of the portal company, as a file download. */
export async function GET(_request: Request, ctx: RouteContext<"/api/invoices/[id]/ubl">) {
  const { id } = await ctx.params;
  const result = await fetchInvoiceUbl(id);
  if (result.kind === "ok") {
    return new Response(result.data, {
      headers: {
        "Content-Type": "application/xml",
        "Content-Disposition": `attachment; filename="invoice-${id.replace(/[^0-9a-f-]/gi, "")}.xml"`,
      },
    });
  }
  const status = result.kind === "unreachable" ? 502 : 404;
  return new Response(null, { status });
}
