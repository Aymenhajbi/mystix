import Link from "next/link";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { t } from "@/app/[lang]/dictionaries";
import type { LogEntry } from "@/lib/api";
import { readableXPath } from "@/lib/highlight";
import { formatDateTime, intlTag, type Locale } from "@/lib/i18n";

const levelClass = { INFO: "sev-info", WARN: "sev-warn", ERROR: "sev-err" } as const;

/**
 * Readable processing log: one line per event with a timestamp to the second, expandable to show what happened,
 * what to do, the failed fields and EN 16931 rules, the request id and the received request.
 */
export function LogList({
  entries,
  lang,
  dict,
  compact = false,
}: {
  entries: LogEntry[];
  lang: Locale;
  dict: Dictionary;
  compact?: boolean;
}) {
  const l = dict.logs;
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });
  return (
    <ul className={styles.logList}>
      {entries.map((e) => {
        const stage = l.stages[e.stage as keyof typeof l.stages] ?? e.stage;
        const fields = e.details?.fieldErrors ?? [];
        const rules = e.details?.ruleViolations ?? [];
        return (
          <li key={e.id}>
            <details className={styles.logItem}>
              <summary className={`${styles.logRow} ${compact ? styles.logRowCompact : ""}`}>
                <time className={styles.time} dateTime={e.occurredAt}>
                  {formatDateTime(lang, e.occurredAt)}
                </time>
                <span className={`${styles.sev} ${styles[levelClass[e.level]]}`}>{l.levels[e.level]}</span>
                {!compact && <span className={styles.stage}>{stage}</span>}
                <span className={styles.logEvent}>
                  <b>{l.events[e.event]}</b>
                  {e.invoiceNumber && (
                    <>
                      {" · "}
                      <bdi className={styles.mono}>{e.invoiceNumber}</bdi>
                    </>
                  )}
                  <span className={styles.logMessage} lang="en">
                    {e.errorCode ? `${e.errorCode} · ` : ""}
                    {e.message}
                  </span>
                </span>
              </summary>
              <div className={styles.logBody}>
                {e.userMessage && (
                  <p>
                    <span className={styles.muted}>{l.what} : </span>
                    {e.userMessage[lang]}
                  </p>
                )}
                {e.suggestedAction && (
                  <p>
                    <span className={styles.muted}>{l.action} : </span>
                    {e.suggestedAction[lang]}
                  </p>
                )}
                <p>
                  <span className={styles.muted}>{l.technical} : </span>
                  <span className={styles.mono} lang="en">
                    {e.message}
                  </span>
                </p>
                {fields.length > 0 && (
                  <div className={styles.tableWrap}>
                    <table className={styles.table}>
                      <caption className={styles.logCaption}>{l.fields}</caption>
                      <thead>
                        <tr>
                          <th scope="col">{l.field}</th>
                          <th scope="col">{l.reason}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {fields.map((f, i) => (
                          <tr key={i}>
                            <td className={styles.mono}>{f.field}</td>
                            <td className={styles.wrapCell} lang="en">
                              {f.reason}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {rules.length > 0 && (
                  <div className={styles.tableWrap}>
                    <table className={styles.table}>
                      <caption className={styles.logCaption}>{l.rules}</caption>
                      <thead>
                        <tr>
                          <th scope="col">{l.rule}</th>
                          <th scope="col">{l.location}</th>
                          <th scope="col">{l.ruleMessage}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rules.map((r, i) => (
                          <tr key={i}>
                            <td>
                              <span className={`${styles.sev} ${r.severity === "FATAL" ? styles["sev-err"] : styles["sev-warn"]}`}>
                                {r.ruleId}
                              </span>
                            </td>
                            <td className={`${styles.mono} ${styles.wrapCell}`} title={r.location}>
                              {readableXPath(r.location)}
                            </td>
                            <td className={styles.wrapCell} lang="en">
                              {r.message}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <dl className={styles.logMeta}>
                  {e.errorCode && (
                    <>
                      <dt>{l.errorCode}</dt>
                      <dd className={styles.mono}>{e.errorCode}</dd>
                    </>
                  )}
                  {e.requestId && (
                    <>
                      <dt>{l.requestId}</dt>
                      <dd>
                        <span className={styles.mono}>{e.requestId}</span>{" "}
                        <span className={styles.muted}>· {l.requestIdHint}</span>
                      </dd>
                    </>
                  )}
                </dl>
                <div className={styles.logLinks}>
                  {e.invoiceId && (
                    <Link href={`/${lang}/invoices/${e.invoiceId}`} className={`${styles.btn} ${styles.btnSm}`}>
                      {l.openInvoice}
                    </Link>
                  )}
                  {e.payloadSize !== null && (
                    <a
                      href={`/api/logs/${e.id}/payload`}
                      className={`${styles.btn} ${styles.btnSm}`}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {t(l.payload, { size: integer.format(e.payloadSize) })}
                    </a>
                  )}
                </div>
              </div>
            </details>
          </li>
        );
      })}
    </ul>
  );
}
