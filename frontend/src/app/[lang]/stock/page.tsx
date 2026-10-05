import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { stockItemHref as itemHref, StockAlertList as AlertList } from "@/components/StockAlerts";
import { formatQuantity, StockBar, StockLegend } from "@/components/StockBar";
import {
  listStockAlerts,
  listStockPositions,
  listStockSnapshots,
  STOCK_STATES,
} from "@/lib/api";
import { formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { getDictionary, t } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/stock">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).stock.title } : {};
}

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v)?.trim() || undefined;

export default async function StockPage({ params, searchParams }: PageProps<"/[lang]/stock">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const s = dict.stock;
  const query = await searchParams;
  const sku = one(query.sku);
  const location = one(query.location);

  const [positions, alerts, snapshots] = await Promise.all([
    listStockPositions(sku, location),
    listStockAlerts(sku, location, 20),
    listStockSnapshots(10),
  ]);
  if (positions.kind === "no-company") {
    return <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>;
  }
  if (positions.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const rows = positions.data;
  const alertRows = alerts.kind === "ok" ? alerts.data : [];
  const snapshotRows = snapshots.kind === "ok" ? snapshots.data : [];
  const last = snapshotRows[0];
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });
  const percent = new Intl.NumberFormat(intlTag(lang), { maximumFractionDigits: 1, numberingSystem: "latn" });
  const quarantined = rows.filter((r) => r.states.QUARANTINE > 0).length;
  const receiptAlerts = alertRows.filter((a) => a.kind === "RECEIPT_DISCREPANCY").length;

  return (
    <div className={styles.stack}>
      <div className={styles.pageHead} style={{ marginBottom: 0 }}>
        <div>
          <h1>{s.title}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "90ch" }}>
            {s.lead}
          </p>
        </div>
      </div>

      <section className={styles.strip} aria-label={s.title}>
        <div>
          <span className={styles.lbl}>{s.kpiPositions}</span>
          <span className={styles.big}>{integer.format(rows.length)}</span>
          <span className={styles.sub}>{s.kpiPositionsSub}</span>
        </div>
        <div>
          <span className={styles.lbl}>{s.kpiQuarantine}</span>
          <span className={`${styles.big} ${quarantined > 0 ? styles.tCrit : ""}`}>{integer.format(quarantined)}</span>
          <span className={styles.sub}>{s.kpiQuarantineSub}</span>
        </div>
        <div>
          <span className={styles.lbl}>{s.kpiAlerts}</span>
          <span className={`${styles.big} ${alertRows.length > 0 ? styles.tCrit : ""}`}>{integer.format(alertRows.length)}</span>
          <span className={styles.sub}>
            {t(s.kpiAlertsSub, { receipt: receiptAlerts, variance: alertRows.length - receiptAlerts })}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{s.kpiAccuracy}</span>
          <span className={styles.big}>
            {last && last.linesCompared > 0 ? percent.format((last.linesMatched / last.linesCompared) * 100) : "—"}
            {last && last.linesCompared > 0 && <small>%</small>}
          </span>
          <span className={styles.sub}>
            {last ? t(s.kpiAccuracySub, { matched: last.linesMatched, compared: last.linesCompared }) : s.kpiAccuracyNone}
          </span>
        </div>
      </section>

      <section className={styles.panel} aria-labelledby="positions-caption">
        <div className={styles.bar}>
          <StockLegend labels={s.states} />
          <form className={styles.decision} action={`/${lang}/stock`} role="search">
            <input
              name="sku"
              defaultValue={sku}
              placeholder={s.filterSku}
              aria-label={s.filterSku}
              className={styles.inlineInput}
              dir="ltr"
            />
            <input
              name="location"
              defaultValue={location}
              placeholder={s.filterLocation}
              aria-label={s.filterLocation}
              className={styles.inlineInput}
              dir="ltr"
            />
            <button type="submit" className={styles.btn}>
              {s.filter}
            </button>
            {(sku || location) && (
              <Link href={`/${lang}/stock`} className={styles.muted} style={{ fontSize: 12.5 }}>
                {s.reset}
              </Link>
            )}
          </form>
        </div>
        {rows.length === 0 ? (
          <p className={styles.empty}>{s.empty}</p>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <caption id="positions-caption">
                <span className={styles.captionRow}>
                  <span>{s.positionsCaption}</span>
                  <span>{t(s.count, { count: rows.length })}</span>
                </span>
              </caption>
              <thead>
                <tr>
                  <th scope="col">{s.sku}</th>
                  <th scope="col">{s.location}</th>
                  {STOCK_STATES.map((state) => (
                    <th key={state} scope="col" className={styles.num}>
                      {s.states[state]}
                    </th>
                  ))}
                  <th scope="col" className={styles.num} title={s.onHandFormula}>
                    {s.onHand}
                  </th>
                  <th scope="col" className={styles.num} title={s.atpFormula}>
                    {s.atp}
                  </th>
                  <th scope="col">{s.split}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.sku}|${r.location}`} className={styles.rowLink}>
                    <th scope="row" style={{ fontWeight: 400 }}>
                      <Link
                        href={itemHref(lang, r.sku, r.location)}
                        className={styles.strongLink}
                        aria-label={t(s.open, { sku: r.sku, location: r.location })}
                      >
                        <bdi className={styles.mono}>{r.sku}</bdi>
                      </Link>
                    </th>
                    <td>
                      <bdi className={styles.mono}>{r.location}</bdi>
                    </td>
                    {STOCK_STATES.map((state) => (
                      <td key={state} className={`${styles.num} ${r.states[state] === 0 ? styles.muted : ""}`}>
                        {formatQuantity(lang, r.states[state])}
                      </td>
                    ))}
                    <td className={styles.num}>
                      <b>{formatQuantity(lang, r.onHand)}</b>
                    </td>
                    <td className={styles.num}>
                      <b>{formatQuantity(lang, r.availableToPromise)}</b>
                    </td>
                    <td style={{ minInlineSize: 120 }}>
                      <StockBar position={r} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className={styles.note} style={{ padding: "12px 18px 14px", margin: 0 }}>
          {s.onHand} = {s.onHandFormula} · {s.atpLong} = {s.atpFormula}. {s.engineNote}
        </p>
      </section>

      <div className={styles.gridTwo}>
        <section className={styles.panel} aria-labelledby="stock-alerts-title">
          <div className={styles.panelHead}>
            <h2 id="stock-alerts-title">{s.alerts}</h2>
            <span className={styles.hint}>{s.alertsHint}</span>
          </div>
          <AlertList lang={lang} dict={dict} alerts={alertRows} />
        </section>
        <section className={styles.panel} aria-labelledby="stock-snapshots-title">
          <div className={styles.panelHead}>
            <h2 id="stock-snapshots-title">{s.snapshots}</h2>
            <span className={styles.hint}>{s.snapshotsHint}</span>
          </div>
          {snapshotRows.length === 0 ? (
            <p className={styles.empty}>{s.noSnapshots}</p>
          ) : (
            <ul className={styles.feed}>
              {snapshotRows.map((snap) => {
                const rate = snap.linesCompared > 0 ? (snap.linesMatched / snap.linesCompared) * 100 : 100;
                return (
                  <li key={snap.eventId}>
                    <time className={styles.time} dateTime={snap.recordedAt}>
                      {formatDateTime(lang, snap.recordedAt)}
                    </time>
                    <span className={`${styles.sev} ${rate === 100 ? styles["sev-ok"] : styles["sev-warn"]}`}>
                      {percent.format(rate)} %
                    </span>
                    <span className={styles.feedText}>
                      {t(s.snapshotLine, { document: snap.documentNumber ?? "—", asOf: formatDateTime(lang, snap.asOf) })}{" "}
                      · {t(s.kpiAccuracySub, { matched: snap.linesMatched, compared: snap.linesCompared })}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
