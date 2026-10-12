import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ApiDown, Badge, Head, one, Qty, QualityBadge } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/stock">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.stock.title} · Atelys` } : {};
}

export default async function AtelysStockPage({ params, searchParams }: PageProps<"/[lang]/atelys/stock">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const query = await searchParams;
  const siteId = one(query.site);
  const search = one(query.q);
  const quality = one(query.quality);
  const [sites, totals, lines] = await Promise.all([
    atelys.sites(),
    atelys.productStock(siteId, search),
    atelys.stockLines({ siteId, q: search, quality }),
  ]);
  if (sites.kind !== "ok" || totals.kind !== "ok" || lines.kind !== "ok") return <ApiDown dict={dict} kind={sites.kind} />;
  const c = a.stockCols;

  return (
    <>
      <Head title={a.stock.title} lead={a.stock.lead} />
      <form className={s.inlineForm} style={{ marginBottom: 16 }} role="search">
        <input name="q" defaultValue={search} placeholder={`${a.common.product}, ${a.common.lot}…`} aria-label={a.common.search} className={s.wide} />
        <select name="site" defaultValue={siteId ?? ""} aria-label={a.common.site}>
          <option value="">{a.common.all}</option>
          {sites.data.map((x) => (
            <option key={x.id} value={x.id}>
              {x.code} · {x.name}
            </option>
          ))}
        </select>
        <select name="quality" defaultValue={quality ?? ""} aria-label={a.lot.title}>
          <option value="">{a.common.all}</option>
          {(["RELEASED", "PENDING", "BLOCKED", "REJECTED"] as const).map((q) => (
            <option key={q} value={q}>
              {a.quality[q]}
            </option>
          ))}
        </select>
        <button type="submit" className={s.ghostBtn}>
          {a.common.filter}
        </button>
      </form>

      <section className={pstyles.panel}>
        <div className={pstyles.panelHead}>
          <h2>{a.stock.byProduct}</h2>
        </div>
        <div className={pstyles.tableWrap}>
          <table className={pstyles.table}>
            <thead>
              <tr>
                <th>{a.common.product}</th>
                <th className={s.num}>{c.physical}</th>
                <th className={s.num}>{c.blocked}</th>
                <th className={s.num}>{c.reserved}</th>
                <th className={s.num}>{c.available}</th>
                <th className={s.num}>{c.inTransit}</th>
              </tr>
            </thead>
            <tbody>
              {totals.data.map((p) => (
                <tr key={p.productId}>
                  <td>
                    <span className={pstyles.mono}>{p.sku}</span> · {p.productName}
                    <div className={pstyles.muted} style={{ fontSize: 12 }}>
                      {a.category[p.category]}
                      {p.belowThreshold && (
                        <>
                          {" "}
                          <Badge tone="warn">{a.stock.below}</Badge>
                        </>
                      )}
                    </div>
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={p.physical} unit={p.unit} />
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={p.blocked} unit={p.unit} />
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={p.reserved} unit={p.unit} />
                  </td>
                  <td className={s.num}>
                    <strong>
                      <Qty lang={lang} value={p.available} unit={p.unit} />
                    </strong>
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={p.inTransit} unit={p.unit} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className={`${s.formula} ${s.bodyFlush}`}>{a.stock.formula}</p>
      </section>

      <section className={pstyles.panel} style={{ marginTop: 16 }}>
        <div className={pstyles.panelHead}>
          <h2>{a.stock.byLot}</h2>
        </div>
        {lines.data.length === 0 ? (
          <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
        ) : (
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.product}</th>
                  <th>{a.common.lot}</th>
                  <th>{a.common.status}</th>
                  <th>{a.common.site}</th>
                  <th>{a.common.location}</th>
                  <th className={s.num}>{a.common.quantity}</th>
                </tr>
              </thead>
              <tbody>
                {lines.data.map((l) => (
                  <tr key={`${l.lotId}-${l.locationId}`}>
                    <td>
                      <span className={pstyles.mono}>{l.sku}</span> · {l.productName}
                    </td>
                    <td>
                      <Link href={`/${lang}/atelys/lots/${l.lotId}`} className={pstyles.mono}>
                        {l.lotNumber}
                      </Link>
                    </td>
                    <td>
                      <QualityBadge quality={l.quality} dict={dict} />
                    </td>
                    <td>{l.siteCode}</td>
                    <td>
                      {l.locationCode} <span className={pstyles.muted}>· {a.locationType[l.locationType]}</span>
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={l.quantity} unit={l.unit} />
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
