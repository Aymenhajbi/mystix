import Link from "next/link";
import styles from "@/app/[lang]/portal.module.css";
import { t, type Dictionary } from "@/app/[lang]/dictionaries";
import type { StockAlert } from "@/lib/api";
import { formatDateTime, type Locale } from "@/lib/i18n";
import { formatQuantity } from "./StockBar";

/** Item page of a position; SKU and location go in the query string (they may contain "/"). */
export const stockItemHref = (lang: Locale, sku: string, location: string) =>
  `/${lang}/stock/item?${new URLSearchParams({ sku, location })}`;

/** Supplier disputes and inventory variances, most recent first. */
export function StockAlertList({ lang, dict, alerts }: { lang: Locale; dict: Pick<Dictionary, "stock">; alerts: StockAlert[] }) {
  const s = dict.stock;
  if (alerts.length === 0) return <p className={styles.empty}>{s.noAlerts}</p>;
  return (
    <ul className={styles.feed}>
      {alerts.map((a, i) => (
        <li key={`${a.eventId}-${a.sku}-${i}`}>
          <time className={styles.time} dateTime={a.recordedAt}>
            {formatDateTime(lang, a.recordedAt)}
          </time>
          <span className={`${styles.sev} ${a.kind === "RECEIPT_DISCREPANCY" ? styles["sev-err"] : styles["sev-warn"]}`}>
            {s.alertKinds[a.kind]}
          </span>
          <span className={styles.feedText}>
            <Link href={stockItemHref(lang, a.sku, a.location)} className={styles.mono}>
              {a.sku}
            </Link>{" "}
            · <bdi>{a.location}</bdi> ·{" "}
            {t(a.kind === "RECEIPT_DISCREPANCY" ? s.announcedAccepted : s.expectedActual, {
              expected: formatQuantity(lang, a.expected),
              actual: formatQuantity(lang, a.actual),
            })}
            <br />
            <span className={`${styles.mono} ${styles.muted}`} style={{ fontSize: 12 }}>
              {a.message}
            </span>
          </span>
        </li>
      ))}
    </ul>
  );
}
