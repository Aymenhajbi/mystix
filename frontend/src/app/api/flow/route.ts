import { getLogStats, listLogs } from "@/lib/api";
import { isFlowWindow } from "@/lib/flow";

/** Live flow data for the portal company: aggregates over the window and the latest log lines. */
export async function GET(request: Request) {
  const param = new URL(request.url).searchParams.get("window") ?? undefined;
  const window = isFlowWindow(param) ? param : "PT24H";
  const [stats, recent] = await Promise.all([getLogStats(window), listLogs({ limit: 15 })]);
  if (stats.kind !== "ok" || recent.kind !== "ok") {
    const status = stats.kind === "no-company" ? 409 : 502;
    return Response.json({ error: stats.kind === "ok" ? recent.kind : stats.kind }, { status });
  }
  return Response.json(
    { window, ...stats.data, recent: recent.data },
    { headers: { "Cache-Control": "no-store" } },
  );
}
