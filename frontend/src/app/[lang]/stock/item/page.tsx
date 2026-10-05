import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { StockAlertList } from "@/components/StockAlerts";
import { formatQuantity, StockBar, StockLegend } from "@/components/StockBar";
import { listStockAlerts, listStockMovements, listStockPositions, STOCK_STATES } from "@/lib/api";
import { formatDateTime, hasLocale } from "@/lib/i18n";
import { getDictionary, t } from "../../dictionaries";
import styles from "../../portal.module.css";

type Params = PageProps<"/[lang]/stock/item">;

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v)?.trim() || undefined;

export async function generateMetadata({ params, searchParams }: Params): Promise<Metadata> {
  const { lang } = await params;
  const sku = one((await searchParams).sku);
  return hasLocale(lang) ? { title: sku ?? (await getDictionary(lang)).stock.title } : {};
}

export default async function StockItemPage({ params, searchParams }: Params) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const s = dict.stock;
  const query = await searchParams;
  const sku = one(query.sku);
  const location = one(query.location);
  if (!sku || !location) notFound();

  const [positions, movements, alerts] = await Promise.all([
    listStockPositions(sku, location),
    listStockMovements(sku, location, 200),
    listStockAlerts(sku, location, 50),
  ]);
  if (positions.kind !== "ok" || movements.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const position = positions.data[0];
  const stateLabel = (state: string | null) =>
    state === null ? s.item.outside : s.states[state as keyof typeof s.states] ?? state;

  return (
    <div className={styles.stack}>
      <div>
        <nav className={styles.crumbs} aria-label={dict.invoice.breadcrumb}>
          <Link href={`/${lang}/stock`}>{s.item.back}</Link>
          <span aria-hidden="true">/</span>
          <bdi aria-current="page" className={styles.mono}>
            {sku}
          </bdi>
        </nav>
        <div className={styles.pageHead} style={{ marginBottom: 0 }}>
          <h1>
            <bdi className={styles.mono}>{t(s.item.title, { sku, location })}</bdi>
          </h1>
        </div>
      </div>

      {!position ? (
        <p className={styles.message}>{s.item.notFound}</p>
      ) : (
        <section className={styles.panel} aria-label={s.title}>
          <div className={styles.form}>
            <div className={styles.stateGrid}>
              {STOCK_STATES.map((state) => (
                <div key={state} className={styles.stateCard}>
                  <small>
                    <i className={styles[`st-${state}`]} style={{ display: "inline-block", inlineSize: 8, blockSize: 8, borderRadius: 2, marginInlineEnd: 6 }} aria-hidden="true" />
                    {s.states[state]}
                  </small>
                  <b>{formatQuantity(lang, position.states[state])}</b>
                  <em>{s.stateHelp[state]}</em>
                </div>
              ))}
              <div className={`${styles.stateCard} ${styles.stateCardKey}`}>
                <small>{s.onHand}</small>
                <b>{formatQuantity(lang, position.onHand)}</b>
                <em>{s.onHandFormula}</em>
              </div>
              <div className={`${styles.stateCard} ${styles.stateCardKey}`}>
                <small>{s.atpLong}</small>
                <b>{formatQuantity(lang, position.availableToPromise)}</b>
                <em>{s.atpFormula}</em>
              </div>
            </div>
            <StockBar position={position} />
            <StockLegend labels={s.states} />
            {position.updatedAt && (
              <p className={styles.formNote} style={{ margin: 0 }}>
                {s.updated} : {formatDateTime(lang, position.updatedAt)}
              </p>
            )}
          </div>
        </section>
      )}

      <section className={styles.panel} aria-labelledby="movements-caption">
        <div className={styles.panelHead}>
          <h2 id="movements-title">{s.item.movements}</h2>
          <span className={styles.hint}>{s.item.movementsHint}</span>
        </div>
        {movements.data.length === 0 ? (
          <p className={styles.empty}>{s.item.noMovements}</p>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <caption id="movements-caption" className={styles.srOnly}>
                {s.item.movements}
              </caption>
              <thead>
                <tr>
                  <th scope="col">{s.item.when}</th>
                  <th scope="col">{s.item.event}</th>
                  <th scope="col">{s.item.document}</th>
                  <th scope="col">{s.item.movement}</th>
                  <th scope="col" className={styles.num}>
                    {s.item.quantity}
                  </th>
                </tr>
              </thead>
              <tbody>
                {movements.data.map((m, i) => (
                  <tr key={`${m.eventId}-${i}`}>
                    <td>
                      <time className={styles.time} dateTime={m.occurredAt}>
                        {formatDateTime(lang, m.occurredAt)}
                      </time>
                    </td>
                    <td>{s.events[m.eventType as keyof typeof s.events] ?? m.eventType}</td>
                    <td>
                      <bdi className={styles.mono}>{m.documentNumber ?? "—"}</bdi>
                    </td>
                    <td>
                      <span className={styles.move}>
                        {stateLabel(m.fromState)}
                        <span aria-hidden="true">{lang === "ar" ? "←" : "→"}</span>
                        <span className={styles.srOnly}>{lang === "ar" ? "إلى" : "vers"}</span>
                        {stateLabel(m.toState)}
                      </span>
                    </td>
                    <td className={styles.num}>{formatQuantity(lang, m.quantity)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className={styles.panel} aria-labelledby="item-alerts-title">
        <div className={styles.panelHead}>
          <h2 id="item-alerts-title">{s.item.alerts}</h2>
        </div>
        <StockAlertList lang={lang} dict={dict} alerts={alerts.kind === "ok" ? alerts.data : []} />
      </section>
    </div>
  );
}
