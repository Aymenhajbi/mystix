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
  const kind = (Array.isArray(query.kind) ? query.kind[0] : query.kind) === "SFTP_POLLING" ? "SFTP_POLLING" : "HTTP_INBOUND";
  const sftp = kind === "SFTP_POLLING";
  const [channels, entryPoints] = await Promise.all([channelsApi.list(), connectivityApi.entryPoints()]);
  // Only channels that can receive with this kind of listener; their status is checked again at start.
  const receiving = (channels.kind === "ok" ? channels.data : []).filter(
    (ch) =>
      ch.direction !== "OUT" && (sftp ? ch.protocol === "SFTP" : ch.protocol === "HTTPS" || ch.protocol === "WEBHOOK"),
  );
  const entries = entryPoints.kind === "ok" ? entryPoints.data : [];

  return (
    <>
      <nav className={styles.crumbs} aria-label="breadcrumb">
        <Link href={`/${lang}/connectivity`}>{c.title}</Link> / <span>{c.newListener}</span>
      </nav>
      <div className={styles.pageHead}>
        <div>
          <h1>{sftp ? c.form.sftpTitle : c.form.listenerTitle}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "75ch" }}>
            {sftp ? c.form.sftpLead : c.form.listenerLead}
          </p>
        </div>
      </div>
      <nav className={styles.filters} aria-label={c.form.kind} style={{ marginBottom: 16 }}>
        {(["HTTP_INBOUND", "SFTP_POLLING"] as const).map((k) => (
          <Link
            key={k}
            href={`/${lang}/connectivity/listeners/new${k === "SFTP_POLLING" ? "?kind=SFTP_POLLING" : ""}`}
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
