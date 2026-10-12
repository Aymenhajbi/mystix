import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../dictionaries";
import pstyles from "../portal.module.css";
import s from "./atelys.module.css";
import { ActionForm, ApiDown, Head, Notice, num, one, pct, Qty, WorkOrderBadge } from "./ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.dashboard.title} · Atelys` } : {};
}

export default async function AtelysDashboard({ params, searchParams }: PageProps<"/[lang]/atelys">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const d = a.dashboard;
  const query = await searchParams;
  const [dashboard, sites] = await Promise.all([atelys.dashboard(), atelys.sites()]);
  if (dashboard.kind !== "ok" || sites.kind !== "ok") return <ApiDown dict={dict} kind={dashboard.kind} />;
  const data = dashboard.data;
  const base = `/${lang}/atelys`;
  const active = (data.workOrdersByStatus.IN_PROGRESS ?? 0) + (data.workOrdersByStatus.RELEASED ?? 0);
  const max = Math.max(1, ...data.lastDays.map((x) => x.good + x.scrap));
  const isDemo = sites.data.some((x) => x.code === "DEMO");

  if (sites.data.length === 0) {
    return (
      <>
        <Head eyebrow={dict.atelys.nav.production} title={d.title} lead={d.lead} />
        <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
        <div className={s.empty}>
          <h2>{d.empty}</h2>
          <p>{d.emptyLead}</p>
          <div className={s.actions}>
            <Link href={`${base}/settings`} className={s.ghostBtn}>
              {a.nav.settings}
            </Link>
            <ActionForm lang={lang} path="/api/v1/atelys/demo" back={base}>
              <button type="submit" className={s.flashBtn}>
                {a.common.loadDemo}
              </button>
            </ActionForm>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <Head eyebrow={dict.atelys.nav.production} title={d.title} lead={d.lead}>
        {isDemo && <span className={s.demoFlag}>{a.common.demoNote}</span>}
        <Link href={`${base}/work-orders`} className={s.flashBtn}>
          {a.workOrders.new}
        </Link>
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <div className={s.kpis}>
        <div className={`${s.kpi} ${s.kpiHot}`}>
          <span>{d.kpiActive}</span>
          <strong>{num(lang, active, 0)}</strong>
        </div>
        <div className={s.kpi}>
          <span>{d.kpiPending}</span>
          <strong>{num(lang, data.lotsPendingQuality, 0)}</strong>
        </div>
        <div className={s.kpi}>
          <span>{d.kpiBlocked}</span>
          <strong>{num(lang, data.lotsBlocked, 0)}</strong>
        </div>
        <div className={s.kpi}>
          <span>{d.kpiAlerts}</span>
          <strong>{num(lang, data.belowThreshold.length, 0)}</strong>
        </div>
      </div>
      <div className={s.grid2}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{d.activeOrders}</h2>
          </div>
          {data.active.length === 0 ? (
            <p className={`${pstyles.muted} ${s.body}`}>
              {a.common.noData}
            </p>
          ) : (
            <div className={pstyles.tableWrap}>
              <table className={pstyles.table}>
                <thead>
                  <tr>
                    <th>{a.common.number}</th>
                    <th>{a.workOrders.product}</th>
                    <th>{a.common.status}</th>
                    <th className={s.num}>{a.workOrders.completion}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.active.map((w) => {
                    const ratio = w.plannedQuantity ? w.producedQuantity / w.plannedQuantity : 0;
                    return (
                      <tr key={w.id}>
                        <td>
                          <Link href={`${base}/work-orders/${w.id}`} className={pstyles.mono}>
                            {w.number}
                          </Link>
                        </td>
                        <td>{w.productName}</td>
                        <td>
                          <WorkOrderBadge status={w.status} dict={dict} />
                        </td>
                        <td className={s.num}>
                          <div style={{ display: "flex", gap: 10, alignItems: "center", justifyContent: "flex-end" }}>
                            <div className={s.progress} aria-hidden="true">
                              <span style={{ width: `${Math.min(100, ratio * 100)}%` }} />
                            </div>
                            {pct(lang, ratio)}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{d.production}</h2>
          </div>
          <div className={s.body}>
            {data.lastDays.length === 0 ? (
              <p className={pstyles.muted}>{a.common.noData}</p>
            ) : (
              <>
                <div className={s.bars} role="img" aria-label={d.production}>
                  {data.lastDays.map((x) => (
                    <div key={x.day} className={s.barCol}>
                      <div className={s.barStack} style={{ height: `${((x.good + x.scrap) / max) * 120}px` }}>
                        <span className={s.barGood} style={{ height: `${(x.good / (x.good + x.scrap || 1)) * 100}%` }} />
                        <span className={s.barScrap} style={{ height: `${(x.scrap / (x.good + x.scrap || 1)) * 100}%` }} />
                      </div>
                      <small>{x.day.slice(5)}</small>
                    </div>
                  ))}
                </div>
                <div className={s.legend}>
                  <span>
                    <i style={{ background: "var(--atelys-flash)" }} />
                    {d.good}
                  </span>
                  <span>
                    <i style={{ background: "var(--crit)" }} />
                    {d.scrap}
                  </span>
                </div>
              </>
            )}
          </div>
        </section>
      </div>
      {data.belowThreshold.length > 0 && (
        <section className={pstyles.panel} style={{ marginTop: 16 }}>
          <div className={pstyles.panelHead}>
            <h2>{d.belowThreshold}</h2>
          </div>
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.product}</th>
                  <th className={s.num}>{a.stockCols.available}</th>
                  <th className={s.num}>{a.settings.threshold}</th>
                </tr>
              </thead>
              <tbody>
                {data.belowThreshold.map((p) => (
                  <tr key={p.productId}>
                    <td>
                      <span className={pstyles.mono}>{p.sku}</span> · {p.productName}
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={p.available} unit={p.unit} />
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={p.alertThreshold} unit={p.unit} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  );
}
