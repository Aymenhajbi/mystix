import { notFound } from "next/navigation";
import { sheetText } from "@/components/channels/ChannelScreens";
import { isUuid } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../../dictionaries";

/** Downloads the exchange sheet of a channel as plain text (ADR-0015). Public data only. */
export async function GET(_request: Request, { params }: RouteContext<"/[lang]/atelys/channels/[channelId]/sheet">) {
  const { lang, channelId } = await params;
  if (!hasLocale(lang) || !isUuid(channelId)) notFound();
  const dict = await getDictionary(lang);
  const text = await sheetText(lang, dict, channelId);
  if (text === null) notFound();
  return new Response(text, {
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Content-Disposition": `attachment; filename="fiche-echange-${channelId.slice(0, 8)}.txt"`,
      "Cache-Control": "no-store",
    },
  });
}
