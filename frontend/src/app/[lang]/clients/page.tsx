import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { listEnvironments, portalCompanyId } from "@/lib/api";
import { formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { getDictionary } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/clients">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).clients.title } : {};
}

export default async function ClientsPage({ params }: PageProps<"/[lang]/clients">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.clients;
  const [environments, active] = await Promise.all([listEnvironments(), portalCompanyId()]);
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1>{c.title}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "80ch" }}>
            {c.lead}
          </p>
        </div>
      </div>
      {environments.kind === "not-found" && <p className={styles.message}>{c.consoleOff}</p>}
      {(environments.kind === "unreachable" || environments.kind === "no-company") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}
      {environments.kind === "ok" && environments.data.length === 0 && <p className={styles.message}>{c.empty}</p>}
      {environments.kind === "ok" && environments.data.length > 0 && (
        <ul className={styles.envGrid}>
          {environments.data.map((e) => {
            const isActive = e.id === active;
            return (
              <li key={e.id}>
                <a
                  className={`${styles.envCard} ${isActive ? styles.envCardActive : ""}`}
                  href={`/api/context?company=${e.id}&next=${encodeURIComponent(`/${lang}/clients/${e.id}`)}`}
                >
                  <span className={styles.envHead}>
                    <span className={styles.envMono} aria-hidden="true">
                      {initials(e.legalName)}
                    </span>
                    <span className={styles.envName}>
                      <b>{e.legalName}</b>
                      <span className={styles.mono}>ICE {e.ice}</span>
                    </span>
                    {isActive && <span className={`${styles.sev} ${styles["sev-ok"]}`}>{c.current}</span>}
                  </span>
                  <span className={styles.envStats}>
                    <span>
                      <small>{c.colFlows}</small>
                      <b>
                        {integer.format(e.activeFlows)}
                        <span className={styles.muted}> / {integer.format(e.flows)}</span>
                      </b>
                    </span>
                    <span>
                      <small>{c.colInvoices}</small>
                      <b>{integer.format(e.invoices)}</b>
                    </span>
                    <span>
                      <small>{c.colErrors}</small>
                      <b className={e.errors24h > 0 ? styles.tCrit : undefined}>{integer.format(e.errors24h)}</b>
                    </span>
                  </span>
                  <span className={styles.stageTime}>
                    {c.colLast} : {e.lastActivity ? formatDateTime(lang, e.lastActivity) : c.never}
                  </span>
                </a>
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter((w) => /^[\p{L}\p{N}]/u.test(w))
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase())
    .join("");
}
