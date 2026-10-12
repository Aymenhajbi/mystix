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

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/shipments">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.shipments.title} · Atelys` } : {};
}

const LINES = 4;

export default async function ShipmentsPage({ params, searchParams }: PageProps<"/[lang]/atelys/shipments">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const S = a.shipments;
  const query = await searchParams;
  const [list, sites, stock] = await Promise.all([atelys.shipments(), atelys.sites(), atelys.stockLines({ quality: "RELEASED" })]);
  if (list.kind !== "ok" || sites.kind !== "ok" || stock.kind !== "ok") return <ApiDown dict={dict} kind={list.kind} />;
  const details = await Promise.all(list.data.slice(0, 30).map((x) => atelys.shipment(x.id)));
  const shipments = details.flatMap((d) => (d.kind === "ok" ? [d.data] : []));
  const page = `/${lang}/atelys/shipments`;
  const pickable = stock.data.filter((l) => !["QUARANTINE", "TRANSIT"].includes(l.locationType));

  return (
    <>
      <Head title={S.title} lead={S.lead}>
        <a href="#new" className={s.flashBtn}>
          {S.new}
        </a>
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <section className={pstyles.panel}>
        {shipments.length === 0 ? (
          <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
        ) : (
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.number}</th>
                  <th>{S.customer}</th>
                  <th>{a.receipts.lines}</th>
                  <th>{a.common.status}</th>
                  <th>{a.common.actions}</th>
                </tr>
              </thead>
              <tbody>
                {shipments.map((x) => (
                  <tr key={x.id}>
                    <td>
                      <span className={pstyles.mono}>{x.number}</span>
                      <div className={pstyles.muted} style={{ fontSize: 12 }}>{when(lang, x.shippedAt ?? x.createdAt)}</div>
                    </td>
                    <td>
                      {x.customer ?? "—"}
                      {x.orderReference && <div className={pstyles.muted} style={{ fontSize: 12 }}>{x.orderReference}</div>}
                    </td>
                    <td>
                      {x.lines.map((l) => (
                        <div key={l.lineNo} style={{ fontSize: 13 }}>
                          <Link href={`/${lang}/atelys/lots/${l.lotId}`} className={pstyles.mono}>
                            {l.sku} · {l.lotNumber}
                          </Link>{" "}
                          · {l.locationCode} · <Qty lang={lang} value={l.picked ?? l.quantity} unit={l.unit} />
                        </div>
                      ))}
                    </td>
                    <td>
                      <Badge tone={x.status === "SHIPPED" ? "ok" : "accent"}>{S.status[x.status]}</Badge>
                    </td>
                    <td>
                      {x.status === "PICKING" && (
                        <ActionForm lang={lang} path={`/api/v1/wms/shipments/${x.id}/confirm`} back={page}>
                          <button type="submit" className={s.flashBtn}>
                            {S.confirm}
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

      <section className={pstyles.panel} style={{ marginTop: 18 }} id="new">
        <div className={pstyles.panelHead}>
          <h2>{S.new}</h2>
        </div>
        <ActionForm lang={lang} path="/api/v1/wms/shipments" back={page} lines="lines" require="lotId" className={s.body}>
          <div className={s.formGrid}>
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
              {S.customer}
              <input name="f.customer" maxLength={200} />
            </label>
            <label>
              {S.orderRef}
              <input name="f.orderReference" maxLength={100} />
            </label>
          </div>
          <div className={s.lines}>
            {Array.from({ length: LINES }, (_, i) => (
              <div key={i} className={s.lineRow} style={{ gridTemplateColumns: "3fr 1fr" }}>
                <select name={`lp.${i}.lotId~locationId`} defaultValue="" aria-label={`${a.common.lot} ${i + 1}`}>
                  <option value="">—</option>
                  {pickable.map((l) => (
                    <option key={`${l.lotId}|${l.locationId}`} value={`${l.lotId}|${l.locationId}`}>
                      {l.sku} · {l.lotNumber} · {l.siteCode}/{l.locationCode} · {num(lang, l.quantity)} {l.unit}
                    </option>
                  ))}
                </select>
                <input name={`ln.${i}.quantity`} type="number" min="0" step="any" aria-label={`${a.common.quantity} ${i + 1}`} />
              </div>
            ))}
          </div>
          <div className={s.formFoot}>
            <button type="submit" className={s.flashBtn}>
              {a.common.create}
            </button>
          </div>
        </ActionForm>
      </section>
    </>
  );
}
