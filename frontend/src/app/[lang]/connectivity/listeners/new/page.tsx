import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { ListenerForm } from "@/components/connectivity/ListenerForm";
import { channelsApi } from "@/lib/channels";
import { connectivityApi } from "@/lib/connectivity";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";
import styles from "../../../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/connectivity/listeners/new">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).connectivity.form.listenerTitle } : {};
}

export default async function NewListenerPage({ params, searchParams }: PageProps<"/[lang]/connectivity/listeners/new">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.connectivity;
  const query = await searchParams;
  const requested = Array.isArray(query.kind) ? query.kind[0] : query.kind;
  const kind = requested === "SFTP_POLLING" || requested === "AS2_SERVER" ? requested : "HTTP_INBOUND";
  const sftp = kind === "SFTP_POLLING";
  const as2 = kind === "AS2_SERVER";
  const [channels, entryPoints] = await Promise.all([channelsApi.list(), connectivityApi.entryPoints()]);
  // Only channels that can receive with this kind of listener; their status is checked again at start.
  const receiving = (channels.kind === "ok" ? channels.data : []).filter(
    (ch) =>
      ch.direction !== "OUT" &&
      (sftp ? ch.protocol === "SFTP" : as2 ? ch.protocol === "AS2" : ch.protocol === "HTTPS" || ch.protocol === "WEBHOOK"),
  );
  const entries = entryPoints.kind === "ok" ? entryPoints.data : [];

  return (
    <>
      <nav className={styles.crumbs} aria-label="breadcrumb">
        <Link href={`/${lang}/connectivity`}>{c.title}</Link> / <span>{c.newListener}</span>
      </nav>
      <div className={styles.pageHead}>
        <div>
          <h1>{sftp ? c.form.sftpTitle : as2 ? c.form.as2Title : c.form.listenerTitle}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "75ch" }}>
            {sftp ? c.form.sftpLead : as2 ? c.form.as2Lead : c.form.listenerLead}
          </p>
        </div>
      </div>
      <nav className={styles.filters} aria-label={c.form.kind} style={{ marginBottom: 16 }}>
        {(["HTTP_INBOUND", "SFTP_POLLING", "AS2_SERVER"] as const).map((k) => (
          <Link
            key={k}
            href={`/${lang}/connectivity/listeners/new${k === "HTTP_INBOUND" ? "" : `?kind=${k}`}`}
            className={styles.filter}
            aria-current={kind === k ? "true" : undefined}
          >
            {c.kinds[k]}
          </Link>
        ))}
      </nav>
      <ListenerForm
        lang={lang}
        dict={dict}
        kind={kind}
        key={kind}
        channels={receiving.map((ch) => ({ id: ch.id, label: `${ch.name} · ${ch.protocol} · ${ch.direction} · ${ch.status}` }))}
        entryPoints={entries.map((e) => ({ id: e.id, label: `${e.code} · ${e.name} (${e.inputFormat})` }))}
      />
    </>
  );
}
