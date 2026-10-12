import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, one, Qty, when } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/receipts">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.receipts.title} · Atelys` } : {};
}

const LINES = 5;

export default async function ReceiptsPage({ params, searchParams }: PageProps<"/[lang]/atelys/receipts">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const R = a.receipts;
  const query = await searchParams;
  const [list, sites, products, locations, units] = await Promise.all([
    atelys.receipts(),
    atelys.sites(),
    atelys.products(),
    atelys.locations(),
    atelys.units(),
  ]);
  if (list.kind !== "ok" || sites.kind !== "ok" || products.kind !== "ok" || locations.kind !== "ok" || units.kind !== "ok") {
    return <ApiDown dict={dict} kind={list.kind} />;
  }
  const details = await Promise.all(list.data.slice(0, 30).map((r) => atelys.receipt(r.id)));
  const receipts = details.flatMap((d) => (d.kind === "ok" ? [d.data] : []));
  const page = `/${lang}/atelys/receipts`;
  const places = locations.data.filter((l) => l.type !== "TRANSIT" && l.type !== "SHIPPING");
  const siteCode = (id: string) => sites.data.find((x) => x.id === id)?.code ?? "";

  return (
    <>
      <Head title={R.title} lead={R.lead}>
        <a href="#new" className={s.flashBtn}>
          {R.new}
        </a>
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <section className={pstyles.panel}>
        {receipts.length === 0 ? (
          <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
        ) : (
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.number}</th>
                  <th>{R.supplier}</th>
                  <th>{R.lines}</th>
                  <th>{a.common.status}</th>
                  <th>{a.common.actions}</th>
                </tr>
              </thead>
              <tbody>
                {receipts.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <span className={pstyles.mono}>{r.number}</span>
                      <div className={pstyles.muted} style={{ fontSize: 12 }}>
                        {siteCode(r.siteId)} · {when(lang, r.confirmedAt ?? r.createdAt)}
                      </div>
                    </td>
                    <td>
                      {r.supplier ?? "—"}
                      {r.sourceDocument && <div className={pstyles.muted} style={{ fontSize: 12 }}>{r.sourceDocument}</div>}
                    </td>
                    <td>
                      {r.lines.map((l) => (
                        <div key={l.lineNo} style={{ fontSize: 13 }}>
                          {l.sku} · <Qty lang={lang} value={l.quantity} unit={l.unit} /> → {l.locationCode}
                          {l.lotId ? (
                            <>
                              {" · "}
                              <Link href={`/${lang}/atelys/lots/${l.lotId}`} className={pstyles.mono}>
                                {l.lotNumber}
                              </Link>
                            </>
                          ) : (
                            l.lotNumber && ` · ${l.lotNumber}`
                          )}
                        </div>
                      ))}
                    </td>
                    <td>
                      <Badge tone={r.status === "CONFIRMED" ? "ok" : "muted"}>{R.status[r.status]}</Badge>
                    </td>
                    <td>
                      {r.status === "DRAFT" && (
                        <ActionForm lang={lang} path={`/api/v1/wms/receipts/${r.id}/confirm`} back={page}>
                          <button type="submit" className={s.flashBtn}>
                            {R.confirm}
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
          <h2>{R.new}</h2>
        </div>
        <ActionForm lang={lang} path="/api/v1/wms/receipts" back={page} lines="lines" className={s.body}>
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
              {R.supplier}
              <input name="f.supplier" maxLength={200} />
            </label>
            <label>
              {R.sourceDocument}
              <input name="f.sourceDocument" maxLength={100} />
            </label>
          </div>
          <div className={s.lines}>
            <div className={s.lineRow}>
              <span className={s.lineHead}>{a.common.product}</span>
              <span className={s.lineHead}>{a.common.quantity}</span>
              <span className={s.lineHead}>{a.common.unit}</span>
              <span className={s.lineHead}>{a.common.location}</span>
              <span className={s.lineHead}>{R.lotNumber}</span>
            </div>
            {Array.from({ length: LINES }, (_, i) => (
              <div key={i} className={s.lineRow}>
                <select name={`l.${i}.productId`} defaultValue="" aria-label={`${a.common.product} ${i + 1}`}>
                  <option value="">—</option>
                  {products.data.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.sku} · {p.name}
                    </option>
                  ))}
                </select>
                <input name={`ln.${i}.quantity`} type="number" min="0" step="any" aria-label={`${a.common.quantity} ${i + 1}`} />
                <select name={`l.${i}.unit`} defaultValue="" aria-label={`${a.common.unit} ${i + 1}`}>
                  <option value="">—</option>
                  {units.data.map((u) => (
                    <option key={u.code} value={u.code}>
                      {u.code}
                    </option>
                  ))}
                </select>
                <select name={`l.${i}.locationId`} defaultValue="" aria-label={`${a.common.location} ${i + 1}`}>
                  <option value="">—</option>
                  {places.map((l) => (
                    <option key={l.id} value={l.id}>
                      {siteCode(l.siteId)} · {l.code}
                    </option>
                  ))}
                </select>
                <input name={`l.${i}.lotNumber`} maxLength={64} aria-label={`${R.lotNumber} ${i + 1}`} />
              </div>
            ))}
          </div>
          <div className={s.formFoot}>
            <button type="submit" className={s.flashBtn}>
              {R.create}
            </button>
            <span className={pstyles.muted} style={{ fontSize: 13 }}>
              {a.common.unit} : {a.settings.baseUnit.toLowerCase()} {a.common.optional}
            </span>
          </div>
        </ActionForm>
      </section>
    </>
  );
}
