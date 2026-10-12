import Link from "next/link";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { getForCompany, type Partner } from "@/lib/api";
import { channelsApi, type ChannelApp } from "@/lib/channels";
import type { Locale } from "@/lib/i18n";
import { ChannelForm } from "./ChannelForm";
import { ChannelNotice, ChannelTable } from "./ChannelTable";

type Query = Record<string, string | string[] | undefined>;

type Common = {
  lang: Locale;
  dict: Dictionary;
  /** The environment chosen in Mystix; absent in Atelys, where the session environment applies. */
  companyId?: string;
  app: ChannelApp;
  /** URL of the channel list of this application. */
  base: string;
  crumbs?: ReactNode;
  query: Query;
};

function Head({ title, lead, note, children }: { title: string; lead: string; note: string; children?: ReactNode }) {
  return (
    <div className={styles.pageHead} style={{ marginBottom: 0 }}>
      <div>
        <h1>{title}</h1>
        <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "80ch" }}>
          {lead}
        </p>
        <p className={styles.declared} role="note" style={{ marginTop: 10 }}>
          {note}
        </p>
      </div>
      {children}
    </div>
  );
}

async function partnersOf(companyId?: string) {
  const partners = await getForCompany<Partner[]>("/api/v1/partners", companyId);
  return partners.kind === "ok" ? partners.data.map((p) => ({ id: p.id, name: p.name })) : [];
}

/** Every channel of the environment, whichever application uses it, so the configuration stays joint. */
export async function ChannelListScreen({ lang, dict, companyId, base, crumbs, query }: Common) {
  const c = dict.channels;
  const [channels, partners] = await Promise.all([channelsApi.list(companyId), partnersOf(companyId)]);
  if (channels.kind === "not-found") notFound();
  if (channels.kind !== "ok") {
    return (
      <p className={styles.message} role="alert">
        {dict.common.apiUnreachable}
      </p>
    );
  }
  const active = channels.data.filter((ch) => ch.status === "ACTIVE").length;
  return (
    <div className={styles.stack}>
      <div>
        {crumbs}
        <Head title={c.title} lead={c.lead} note={c.transportNote}>
          <Link href={`${base}/new`} className={`${styles.btn} ${styles.btnPrimary}`}>
            {c.add}
          </Link>
        </Head>
      </div>
      <ChannelNotice dict={dict} query={query} />
      <section className={styles.panel} aria-labelledby="channels-title">
        <div className={styles.panelHead}>
          <h2 id="channels-title">{c.title}</h2>
          <span className={styles.hint}>
            {c.summary.replace("{count}", String(channels.data.length)).replace("{active}", String(active))}
          </span>
        </div>
        <ChannelTable
          lang={lang}
          dict={dict}
          channels={channels.data}
          partners={new Map(partners.map((p) => [p.id, p.name]))}
          href={(id) => `${base}/${id}`}
        />
      </section>
    </div>
  );
}

/** Create (no channelId) or edit a channel. */
export async function ChannelEditScreen({
  lang,
  dict,
  companyId,
  app,
  base,
  crumbs,
  query,
  channelId,
}: Common & { channelId?: string }) {
  const c = dict.channels;
  const [catalog, partners, channel] = await Promise.all([
    channelsApi.catalog(companyId),
    partnersOf(companyId),
    channelId ? channelsApi.get(channelId, companyId) : Promise.resolve(null),
  ]);
  if (channel && channel.kind === "not-found") notFound();
  if (catalog.kind !== "ok" || (channel && channel.kind !== "ok")) {
    return (
      <p className={styles.message} role="alert">
        {dict.common.apiUnreachable}
      </p>
    );
  }
  const existing = channel && channel.kind === "ok" ? channel.data : undefined;
  const page = existing ? `${base}/${existing.id}` : `${base}/new`;
  return (
    <div className={styles.stack}>
      <div>
        {crumbs}
        <Head title={existing ? existing.name : c.add} lead={c.lead} note={c.transportNote}>
          <Link href={base} className={styles.btn}>
            {c.back}
          </Link>
        </Head>
      </div>
      <ChannelNotice dict={dict} query={query} />
      <section className={styles.panel}>
        <ChannelForm
          lang={lang}
          labels={c}
          catalog={catalog.data}
          partners={partners}
          channel={existing}
          companyId={companyId}
          base={base}
          back={page}
          defaultApp={app}
        />
      </section>
    </div>
  );
}
