import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, num, one, when } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/boms">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.boms.title} · Atelys` } : {};
}

const LINES = 6;

export default async function BomsPage({ params, searchParams }: PageProps<"/[lang]/atelys/boms">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const b = a.boms;
  const query = await searchParams;
  const [list, products] = await Promise.all([atelys.boms(), atelys.products()]);
  if (list.kind !== "ok" || products.kind !== "ok") return <ApiDown dict={dict} kind={list.kind} />;
  const details = await Promise.all(list.data.map((x) => atelys.bom(x.id)));
  const boms = details.flatMap((d) => (d.kind === "ok" ? [d.data] : []));
  const page = `/${lang}/atelys/boms`;
  const made = products.data.filter((p) => ["FINISHED", "SEMI_FINISHED", "BY_PRODUCT"].includes(p.category));
  const tone = { DRAFT: "muted", VALIDATED: "ok", OBSOLETE: "warn" } as const;

  return (
    <>
      <Head eyebrow={dict.atelys.nav.production} title={b.title} lead={b.lead} />
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <div style={{ display: "grid", gap: 14 }}>
        {boms.length === 0 && <p className={pstyles.muted}>{a.common.noData}</p>}
        {boms.map((bom) => (
          <section key={bom.id} className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>
                {bom.productName} <span className={pstyles.muted}>· {b.version} {bom.version}</span>
              </h2>
              <Badge tone={tone[bom.status]}>{b.status[bom.status]}</Badge>
              <span className={pstyles.muted} style={{ fontSize: 12.5 }}>
                {b.referenceQty} {num(lang, bom.referenceQuantity)} {bom.unit}
                {bom.validatedAt && ` · ${when(lang, bom.validatedAt)} · ${bom.validatedBy ?? ""}`}
              </span>
              <div className={pstyles.right}>
                {bom.status === "DRAFT" && (
                  <ActionForm lang={lang} path={`/api/v1/gpao/boms/${bom.id}/validate`} back={page}>
                    <button type="submit" className={s.flashBtn}>
                      {b.validate}
                    </button>
                  </ActionForm>
                )}
                {bom.status !== "OBSOLETE" && (
                  <ActionForm lang={lang} path={`/api/v1/gpao/boms/${bom.id}/obsolete`} back={page}>
                    <button type="submit" className={`${s.ghostBtn} ${s.dangerBtn}`}>
                      {b.obsolete}
                    </button>
                  </ActionForm>
                )}
              </div>
            </div>
            <div className={pstyles.tableWrap}>
              <table className={pstyles.table}>
                <thead>
                  <tr>
                    <th>{b.component}</th>
                    <th className={s.num}>{b.perReference}</th>
                    <th className={s.num}>{b.loss}</th>
                  </tr>
                </thead>
                <tbody>
                  {bom.lines.map((l) => (
                    <tr key={l.lineNo}>
                      <td>
                        <span className={pstyles.mono}>{l.sku}</span> · {l.name}
                      </td>
                      <td className={s.num}>
                        {num(lang, l.quantity, 6)}
                        <span className={s.unit}>{l.unit}</span>
                      </td>
                      <td className={s.num}>{num(lang, l.lossPercent, 3)} %</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {bom.note && <p className={`${pstyles.muted} ${s.bodyFlush}`} style={{ margin: 0, fontSize: 13 }}>{bom.note}</p>}
          </section>
        ))}
      </div>

      <section className={pstyles.panel} style={{ marginTop: 18 }} id="new">
        <div className={pstyles.panelHead}>
          <h2>{b.new}</h2>
        </div>
        <ActionForm lang={lang} path="/api/v1/gpao/boms" back={page} lines="lines" className={s.body}>
          <div className={s.formGrid}>
            <label>
              {b.product}
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
              {b.referenceQty}
              <input name="n.referenceQuantity" type="number" min="0.001" step="any" defaultValue="1" required />
            </label>
            <label style={{ gridColumn: "span 2" }}>
              {b.note}
              <input name="f.note" maxLength={500} />
            </label>
          </div>
          <div className={s.lines}>
            <div className={s.lineRow} style={{ gridTemplateColumns: "2fr 1fr 1fr" }}>
              <span className={s.lineHead}>{b.component}</span>
              <span className={s.lineHead}>{b.perReference}</span>
              <span className={s.lineHead}>{b.loss}</span>
            </div>
            {Array.from({ length: LINES }, (_, i) => (
              <div key={i} className={s.lineRow} style={{ gridTemplateColumns: "2fr 1fr 1fr" }}>
                <select name={`l.${i}.componentId`} defaultValue="" aria-label={`${b.component} ${i + 1}`}>
                  <option value="">—</option>
                  {products.data.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.sku} · {p.name} ({p.baseUnit})
                    </option>
                  ))}
                </select>
                <input name={`ln.${i}.quantity`} type="number" min="0" step="any" aria-label={`${b.perReference} ${i + 1}`} />
                <input name={`ln.${i}.lossPercent`} type="number" min="0" max="99" step="any" aria-label={`${b.loss} ${i + 1}`} />
              </div>
            ))}
          </div>
          <div className={s.formFoot}>
            <button type="submit" className={s.flashBtn}>
              {b.create}
            </button>
          </div>
        </ActionForm>
      </section>
    </>
  );
}
