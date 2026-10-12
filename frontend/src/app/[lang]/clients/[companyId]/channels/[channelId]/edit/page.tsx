import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { ChannelEditScreen } from "@/components/channels/ChannelScreens";
import { getCompany, isUuid } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../../../dictionaries";
import styles from "../../../../../portal.module.css";

type Params = PageProps<"/[lang]/clients/[companyId]/channels/[channelId]/edit">;

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).channels.title } : {};
}

/** Communication channels of one client environment, from Mystix (ADR-0015). */
export default async function Page({ params, searchParams }: Params) {
  const { lang, companyId, channelId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId) || !isUuid(channelId)) notFound();
  await connection();
  const [dict, company, query] = await Promise.all([getDictionary(lang), getCompany(companyId), searchParams]);
  if (company.kind === "not-found") notFound();
  const base = `/${lang}/clients/${companyId}/channels`;
  const crumbs = (
    <nav className={styles.crumbs} aria-label={dict.clients.breadcrumb}>
      <Link href={`/${lang}/clients`}>{dict.clients.title}</Link>
      <span aria-hidden="true">/</span>
      <Link href={`/${lang}/clients/${companyId}`}>{company.kind === "ok" ? company.data.legalName : companyId}</Link>
      <span aria-hidden="true">/</span>
      <Link href={base}>{dict.channels.title}</Link>
    </nav>
  );
  return (
    <ChannelEditScreen
      lang={lang}
      dict={dict}
      companyId={companyId}
      app="MYSTIX"
      base={base}
      crumbs={crumbs}
      query={query}
      channelId={channelId}
    />
  );
}
