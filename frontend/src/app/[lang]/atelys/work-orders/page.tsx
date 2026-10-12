import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Head, Notice, one, pct, Qty, WorkOrderBadge } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/work-orders">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.workOrders.title} · Atelys` } : {};
}

export default async function WorkOrdersPage({ params, searchParams }: PageProps<"/[lang]/atelys/work-orders">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const w = a.workOrders;
  const query = await searchParams;
  const status = one(query.status);
  const [orders, products, sites, locations] = await Promise.all([
    atelys.workOrders(status),
    atelys.products(),
    atelys.sites(),
    atelys.locations(),
  ]);
  if (orders.kind !== "ok" || products.kind !== "ok" || sites.kind !== "ok" || locations.kind !== "ok") {
    return <ApiDown dict={dict} kind={orders.kind} />;
  }
  const base = `/${lang}/atelys/work-orders`;
  const made = products.data.filter((p) => ["FINISHED", "SEMI_FINISHED", "BY_PRODUCT"].includes(p.category));
  const outputs = locations.data.filter((l) => l.type !== "TRANSIT");
  const statuses = ["IN_PROGRESS", "RELEASED", "PLANNED", "DRAFT", "ON_HOLD", "COMPLETED", "CLOSED"] as const;

  return (
    <>
      <Head eyebrow={dict.atelys.nav.production} title={w.title} lead={w.lead} />
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <nav className={pstyles.dirTabs} aria-label={a.common.filter} style={{ marginBottom: 14 }}>
        <Link href={base} aria-current={!status ? "page" : undefined}>
          {a.common.all}
        </Link>
        {statuses.map((st) => (
          <Link key={st} href={`${base}?status=${st}`} aria-current={status === st ? "page" : undefined}>
            {a.woStatus[st]}
          </Link>
        ))}
      </nav>
      <section className={pstyles.panel}>
        {orders.data.length === 0 ? (
          <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
        ) : (
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.number}</th>
                  <th>{w.product}</th>
                  <th>{a.common.status}</th>
                  <th className={s.num}>{w.planned}</th>
                  <th className={s.num}>{w.good}</th>
                  <th className={s.num}>{w.scrap}</th>
                  <th className={s.num}>{w.completion}</th>
                  <th>{w.line}</th>
                  <th>{w.start}</th>
                </tr>
              </thead>
              <tbody>
                {orders.data.map((o) => (
                  <tr key={o.id}>
                    <td>
                      <Link href={`${base}/${o.id}`} className={pstyles.mono}>
                        {o.number}
                      </Link>
                    </td>
                    <td>
                      {o.productName}
                      <div className={pstyles.muted} style={{ fontSize: 12 }}>
                        {o.sku} · {w.bom} v{o.bomVersion}
                      </div>
                    </td>
                    <td>
                      <WorkOrderBadge status={o.status} dict={dict} />
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={o.plannedQuantity} unit={o.unit} />
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={o.goodQuantity} unit={o.unit} />
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={o.scrapQuantity} unit={o.unit} />
                    </td>
                    <td className={s.num}>{pct(lang, o.plannedQuantity ? o.producedQuantity / o.plannedQuantity : null)}</td>
                    <td>{o.productionLine ?? "—"}</td>
                    <td>{o.plannedStart ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className={pstyles.panel} style={{ marginTop: 18 }} id="new">
        <div className={pstyles.panelHead}>
          <h2>{w.new}</h2>
        </div>
        <ActionForm lang={lang} path="/api/v1/gpao/work-orders" back={base} next={`${base}/{id}`} className={s.body}>
          <div className={s.formGrid}>
            <label>
              {w.product}
              <select name="f.productId" required defaultValue="">
                <option value="" disabled>
                  {a.common.choose}
                </option>
                {made.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.sku} · {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {w.planned}
              <input name="n.plannedQuantity" type="number" min="0.001" step="any" required inputMode="decimal" />
            </label>
            <label>
              {a.common.site}
              <select name="f.siteId" required defaultValue={sites.data[0]?.id ?? ""}>
                {sites.data.map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.code} · {x.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {w.output}
              <select name="f.outputLocationId" required defaultValue="">
                <option value="" disabled>
                  {a.common.choose}
                </option>
                {outputs.map((l) => (
                  <option key={l.id} value={l.id}>
                    {sites.data.find((x) => x.id === l.siteId)?.code} · {l.code} ({a.locationType[l.type]})
                  </option>
                ))}
              </select>
            </label>
            <label>
              {w.line}
              <input name="f.productionLine" maxLength={80} />
            </label>
            <label>
              {w.priority}
              <select name="n.priority" defaultValue="3">
                {[1, 2, 3, 4, 5].map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {w.start}
              <input name="f.plannedStart" type="date" />
            </label>
            <label>
              {w.end}
              <input name="f.plannedEnd" type="date" />
            </label>
            <label>
              {w.customerRef}
              <input name="f.customerReference" maxLength={100} />
            </label>
          </div>
          <div className={s.formFoot}>
            <button type="submit" className={s.flashBtn}>
              {w.create}
            </button>
            <span className={pstyles.muted} style={{ fontSize: 13 }}>
              {w.bom} : {a.boms.status.VALIDATED.toLowerCase()}
            </span>
          </div>
        </ActionForm>
      </section>
    </>
  );
}
