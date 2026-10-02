import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { Icon } from "@/components/Icons";
import { LogList } from "@/components/LogList";
import { LOG_WINDOW, listLogs, type LogEntry } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary, t } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/logs">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).logs.title } : {};
}

type LevelFilter = "all" | "ERROR" | "INFO";
const levelFilters: LevelFilter[] = ["all", "ERROR", "INFO"];

function matches(entry: LogEntry, level: LevelFilter, q: string) {
  if (level !== "all" && entry.level !== level) return false;
  const query = q.trim().toLowerCase();
  if (!query) return true;
  return [entry.invoiceNumber, entry.errorCode, entry.requestId, entry.message, entry.event]
    .filter(Boolean)
    .some((v) => (v as string).toLowerCase().includes(query));
}

export default async function LogsPage({ params, searchParams }: PageProps<"/[lang]/logs">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const l = dict.logs;
  const query = await searchParams;
  const rawLevel = Array.isArray(query.level) ? query.level[0] : query.level;
  const level: LevelFilter = levelFilters.includes(rawLevel as LevelFilter) ? (rawLevel as LevelFilter) : "all";
  const q = (Array.isArray(query.q) ? query.q[0] : query.q) ?? "";
  const result = await listLogs();

  const href = (filter: LevelFilter) => {
    const p = new URLSearchParams();
    if (filter !== "all") p.set("level", filter);
    if (q) p.set("q", q);
    const s = p.toString();
    return `/${lang}/logs${s ? `?${s}` : ""}`;
  };

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1>{l.title}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "70ch" }}>
            {l.lead}
          </p>
        </div>
      </div>

      {result.kind === "no-company" && <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>}
      {(result.kind === "unreachable" || result.kind === "not-found") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}

      {result.kind === "ok" && (
        <section className={styles.panel} aria-label={l.title}>
          <div className={styles.bar}>
            <nav className={styles.filters} aria-label={l.filterLabel}>
              {levelFilters.map((filter) => (
                <Link
                  key={filter}
                  href={href(filter)}
                  className={styles.filter}
                  aria-current={level === filter ? "true" : undefined}
                >
                  {l.filters[filter]}{" "}
                  <span className={styles.count}>· {result.data.filter((e) => matches(e, filter, q)).length}</span>
                </Link>
              ))}
            </nav>
            <form className={styles.searchBox} action={`/${lang}/logs`} role="search">
              {level !== "all" && <input type="hidden" name="level" value={level} />}
              <Icon name="search" size={15} />
              <input type="search" name="q" defaultValue={q} placeholder={l.search} aria-label={l.search} />
            </form>
          </div>
          {result.data.length === 0 ? (
            <p className={styles.empty}>{l.none}</p>
          ) : (
            (() => {
              const visible = result.data.filter((e) => matches(e, level, q));
              return (
                <>
                  <div className={styles.captionRow}>
                    <span>{t(l.count, { shown: visible.length, total: result.data.length })}</span>
                    {result.data.length >= LOG_WINDOW && <span>{t(l.window, { count: LOG_WINDOW })}</span>}
                  </div>
                  {visible.length === 0 ? (
                    <p className={styles.empty}>{l.empty}</p>
                  ) : (
                    <LogList entries={visible} lang={lang} dict={dict} />
                  )}
                </>
              );
            })()
          )}
        </section>
      )}
    </>
  );
}
