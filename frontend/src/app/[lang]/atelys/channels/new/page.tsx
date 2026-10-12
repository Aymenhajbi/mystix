import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { ChannelEditScreen } from "@/components/channels/ChannelScreens";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";

type Params = PageProps<"/[lang]/atelys/channels/new">;

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).channels.title} · Atelys` } : {};
}

/** The same channels as in Mystix, for the active environment of the session (ADR-0015). */
export default async function Page({ params, searchParams }: Params) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const [dict, query] = await Promise.all([getDictionary(lang), searchParams]);
  return (
    <ChannelEditScreen
      lang={lang}
      dict={dict}
      app="ATELYS"
      base={`/${lang}/atelys/channels`}
      query={query}
    />
  );
}
