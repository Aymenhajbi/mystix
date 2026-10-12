import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, num, one, Qty, when } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/transfers">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.transfers.title} · Atelys` } : {};
}

export default async function TransfersPage({ params, searchParams }: PageProps<"/[lang]/atelys/transfers">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const T = a.transfers;
  const query = await searchParams;
  const [list, stock, locations, sites] = await Promise.all([
    atelys.transfers(),
    atelys.stockLines({}),
    atelys.locations(),
    atelys.sites(),
  ]);
  if (list.kind !== "ok" || stock.kind !== "ok" || locations.kind !== "ok" || sites.kind !== "ok") {
    return <ApiDown dict={dict} kind={list.kind} />;
  }
  const page = `/${lang}/atelys/transfers`;
  const siteCode = (id: string) => sites.data.find((x) => x.id === id)?.code ?? "";
  const tone = { DRAFT: "muted", SHIPPED: "warn", RECEIVED: "ok", CANCELLED: "crit" } as const;
  const movable = stock.data.filter((l) => l.locationType !== "TRANSIT");
  const targets = locations.data.filter((l) => l.type !== "TRANSIT");

  return (
    <>
      <Head title={T.title} lead={T.lead} />
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <section className={pstyles.panel}>
        <div className={pstyles.panelHead}>
          <h2>{T.new}</h2>
        </div>
        <ActionForm lang={lang} path="/api/v1/wms/transfers" back={page} className={s.body}>
          <div className={s.formGrid}>
            <label style={{ gridColumn: "span 2" }}>
              {T.stockLine}
              <select name="p.lotId~fromLocationId" required defaultValue="">
                <option value="" disabled>
                  {a.common.choose}
                </option>
                {movable.map((l) => (
                  <option key={`${l.lotId}|${l.locationId}`} value={`${l.lotId}|${l.locationId}`}>
                    {l.sku} · {l.lotNumber} · {l.siteCode}/{l.locationCode} · {num(lang, l.quantity)} {l.unit}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {a.common.quantity}
              <input name="n.quantity" type="number" min="0.001" step="any" required />
            </label>
            <label>
              {T.to}
              <select name="f.toLocationId" required defaultValue="">
                <option value="" disabled>
                  {a.common.choose}
                </option>
                {targets.map((l) => (
                  <option key={l.id} value={l.id}>
                    {siteCode(l.siteId)} · {l.code} ({a.locationType[l.type]})
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className={s.formFoot}>
            <button type="submit" className={s.flashBtn}>
              {a.common.create}
            </button>
          </div>
        </ActionForm>
      </section>

      <section className={pstyles.panel} style={{ marginTop: 16 }}>
        {list.data.length === 0 ? (
          <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
        ) : (
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.number}</th>
                  <th>{a.common.lot}</th>
                  <th>{T.from}</th>
                  <th>{T.to}</th>
                  <th className={s.num}>{a.common.quantity}</th>
                  <th>{a.common.status}</th>
                  <th>{a.common.actions}</th>
                </tr>
              </thead>
              <tbody>
                {list.data.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <span className={pstyles.mono}>{t.number}</span>
                      <div className={pstyles.muted} style={{ fontSize: 12 }}>{when(lang, t.createdAt)}</div>
                    </td>
                    <td>
                      <Link href={`/${lang}/atelys/lots/${t.lotId}`} className={pstyles.mono}>
                        {t.sku} · {t.lotNumber}
                      </Link>
                    </td>
                    <td>
                      {siteCode(t.fromSiteId)} · {t.fromLocation}
                    </td>
                    <td>
                      {siteCode(t.toSiteId)} · {t.toLocation}
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={t.quantity} unit={t.unit} />
                    </td>
                    <td>
                      <Badge tone={tone[t.status]}>{T.status[t.status]}</Badge>
                    </td>
                    <td>
                      {t.status === "DRAFT" && (
                        <ActionForm lang={lang} path={`/api/v1/wms/transfers/${t.id}/ship`} back={page}>
                          <button type="submit" className={s.flashBtn}>
                            {T.ship}
                          </button>
                        </ActionForm>
                      )}
                      {t.status === "SHIPPED" && (
                        <ActionForm lang={lang} path={`/api/v1/wms/transfers/${t.id}/receive`} back={page}>
                          <button type="submit" className={s.flashBtn}>
                            {T.receive}
                          </button>
                        </ActionForm>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
