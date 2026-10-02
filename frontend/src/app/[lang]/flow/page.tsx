import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { LiveFlow } from "@/components/LiveFlow";
import { getLogStats, listLogs } from "@/lib/api";
import { isFlowWindow } from "@/lib/flow";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/flow">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).flow.title } : {};
}

export default async function FlowPage({ params, searchParams }: PageProps<"/[lang]/flow">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const query = await searchParams;
  const raw = Array.isArray(query.window) ? query.window[0] : query.window;
  const window = isFlowWindow(raw) ? raw : "PT24H";
  const [stats, recent] = await Promise.all([getLogStats(window), listLogs({ limit: 15 })]);

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1>{dict.flow.title}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "75ch" }}>
            {dict.flow.lead}
          </p>
        </div>
      </div>
      {stats.kind === "no-company" && <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>}
      {stats.kind !== "no-company" && (stats.kind !== "ok" || recent.kind !== "ok") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}
      {stats.kind === "ok" && recent.kind === "ok" && (
        <LiveFlow
          key={window}
          lang={lang}
          dict={{ flow: dict.flow, logs: dict.logs }}
          initial={{ window, ...stats.data, recent: recent.data }}
        />
      )}
    </>
  );
}
