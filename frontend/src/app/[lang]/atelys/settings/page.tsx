import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Head, Notice, num, one, QualityBadge } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/settings">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.settings.title} · Atelys` } : {};
}

export default async function SettingsPage({ params, searchParams }: PageProps<"/[lang]/atelys/settings">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const S = a.settings;
  const query = await searchParams;
  const [units, sites, warehouses, locations, products] = await Promise.all([
    atelys.units(),
    atelys.sites(),
    atelys.warehouses(),
    atelys.locations(),
    atelys.products(),
  ]);
  if (units.kind !== "ok" || sites.kind !== "ok" || warehouses.kind !== "ok" || locations.kind !== "ok" || products.kind !== "ok") {
    return <ApiDown dict={dict} kind={units.kind} />;
  }
  const page = `/${lang}/atelys/settings`;
  const siteCode = (id: string) => sites.data.find((x) => x.id === id)?.code ?? "";
  const whCode = (id: string | null) => warehouses.data.find((x) => x.id === id)?.code ?? "—";

  return (
    <>
      <Head title={S.title} lead={S.lead} />
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <div className={s.grid2}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{S.units}</h2>
          </div>
          <div className={s.bodyFlush} style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {units.data.map((u) => (
              <span key={u.code} className={`${s.badge} ${s["st-muted"]}`}>
                {u.code} · {u.name} · {S.dimensions[u.dimension as keyof typeof S.dimensions]}
              </span>
            ))}
          </div>
          <ActionForm lang={lang} path="/api/v1/wms/units" back={page} className={s.body}>
            <div className={s.inlineForm}>
              <input name="f.code" required maxLength={16} placeholder={S.code} aria-label={S.code} style={{ width: 90 }} />
              <input name="f.name" required maxLength={80} placeholder={S.name} aria-label={S.name} />
              <select name="f.dimension" aria-label={S.dimension}>
                {Object.entries(S.dimensions).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
              <button type="submit" className={s.ghostBtn}>
                {a.common.create}
              </button>
            </div>
          </ActionForm>
        </section>

        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{S.sites}</h2>
          </div>
          <ul className={s.bodyFlush} style={{ margin: 0, paddingInlineStart: 32, display: "grid", gap: 4 }}>
            {sites.data.map((x) => (
              <li key={x.id}>
                <strong>{x.code}</strong> · {x.name}
              </li>
            ))}
          </ul>
          <ActionForm lang={lang} path="/api/v1/wms/sites" back={page} className={s.body}>
            <div className={s.inlineForm}>
              <input name="f.code" required maxLength={32} placeholder={S.code} aria-label={S.code} style={{ width: 110 }} />
              <input name="f.name" required maxLength={120} placeholder={S.name} aria-label={S.name} />
              <button type="submit" className={s.ghostBtn}>
                {a.common.create}
              </button>
            </div>
          </ActionForm>
        </section>
      </div>

      <div className={s.grid2} style={{ marginTop: 16 }}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{S.warehouses}</h2>
          </div>
          <ul className={s.bodyFlush} style={{ margin: 0, paddingInlineStart: 32, display: "grid", gap: 4 }}>
            {warehouses.data.map((w) => (
              <li key={w.id}>
                {siteCode(w.siteId)} · <strong>{w.code}</strong> · {w.name}
              </li>
            ))}
          </ul>
          {sites.data.map((x) => (
            <ActionForm key={x.id} lang={lang} path={`/api/v1/wms/sites/${x.id}/warehouses`} back={page} className={s.bodyFlush}>
              <div className={s.inlineForm}>
                <span className={pstyles.muted} style={{ minWidth: 60 }}>{x.code}</span>
                <input name="f.code" required maxLength={32} placeholder={S.code} aria-label={`${S.code} ${x.code}`} style={{ width: 90 }} />
                <input name="f.name" required maxLength={120} placeholder={S.name} aria-label={`${S.name} ${x.code}`} />
                <button type="submit" className={s.ghostBtn}>
                  {a.common.create}
                </button>
              </div>
            </ActionForm>
          ))}
        </section>

        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{S.locations}</h2>
          </div>
          <div className={pstyles.tableWrap} style={{ maxHeight: 280, overflowY: "auto" }}>
            <table className={pstyles.table}>
              <tbody>
                {locations.data.map((l) => (
                  <tr key={l.id}>
                    <td>{siteCode(l.siteId)}</td>
                    <td>{whCode(l.warehouseId)}</td>
                    <td className={pstyles.mono}>{l.code}</td>
                    <td>{a.locationType[l.type]}</td>
                    <td>{l.zone ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {warehouses.data.map((w) => (
            <ActionForm key={w.id} lang={lang} path={`/api/v1/wms/warehouses/${w.id}/locations`} back={page} className={s.bodyFlush}>
              <div className={s.inlineForm}>
                <span className={pstyles.muted} style={{ minWidth: 80 }}>
                  {siteCode(w.siteId)}/{w.code}
                </span>
                <input name="f.code" required maxLength={40} placeholder={S.code} aria-label={`${S.code} ${w.code}`} style={{ width: 100 }} />
                <input name="f.zone" maxLength={40} placeholder={S.zone} aria-label={`${S.zone} ${w.code}`} style={{ width: 110 }} />
                <select name="f.type" aria-label={`${a.common.type} ${w.code}`}>
                  {(["STORAGE", "RECEIVING", "PRODUCTION", "QUARANTINE", "SHIPPING"] as const).map((t) => (
                    <option key={t} value={t}>
                      {a.locationType[t]}
                    </option>
                  ))}
                </select>
                <button type="submit" className={s.ghostBtn}>
                  {a.common.create}
                </button>
              </div>
            </ActionForm>
          ))}
        </section>
      </div>

      <section className={pstyles.panel} style={{ marginTop: 16 }}>
        <div className={pstyles.panelHead}>
          <h2>{S.products}</h2>
        </div>
        <div className={pstyles.tableWrap}>
          <table className={pstyles.table}>
            <thead>
              <tr>
                <th>{S.sku}</th>
                <th>{S.name}</th>
                <th>{a.common.type}</th>
                <th>{S.baseUnit}</th>
                <th>{S.lotTracked}</th>
                <th>{S.receiptQuality}</th>
                <th className={s.num}>{S.threshold}</th>
                <th>{S.conversion}</th>
              </tr>
            </thead>
            <tbody>
              {products.data.map((p) => (
                <tr key={p.id}>
                  <td className={pstyles.mono}>{p.sku}</td>
                  <td>{p.name}</td>
                  <td>{a.category[p.category]}</td>
                  <td>{p.baseUnit}</td>
                  <td>{p.lotTracked ? "✓" : "—"}</td>
                  <td>
                    <QualityBadge quality={p.receiptQuality} dict={dict} />
                  </td>
                  <td className={s.num}>{p.alertThreshold == null ? "—" : num(lang, p.alertThreshold)}</td>
                  <td>
                    {Object.entries(p.conversions).map(([u, f]) => (
                      <div key={u} style={{ fontSize: 12.5 }}>
                        1 {u} = {num(lang, f, 6)} {p.baseUnit}
                      </div>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <ActionForm lang={lang} method="PUT" path="/api/v1/wms/products/{product}/conversions/{unit}" back={page} className={s.bodyFlush}>
          <div className={s.inlineForm}>
            <span className={pstyles.muted}>{S.conversion}</span>
            <select name="product" required aria-label={a.common.product} className={s.wide}>
              {products.data.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.sku} ({p.baseUnit})
                </option>
              ))}
            </select>
            <span>1</span>
            <select name="unit" required aria-label={a.common.unit}>
              {units.data.map((u) => (
                <option key={u.code} value={u.code}>
                  {u.code}
                </option>
              ))}
            </select>
            <span>=</span>
            <input name="n.factor" type="number" min="0.000001" step="any" required aria-label={S.factor} placeholder={S.factor} style={{ width: 150 }} />
            <input name="f.note" maxLength={200} placeholder={dict.atelys.boms.note} aria-label={dict.atelys.boms.note} />
            <button type="submit" className={s.ghostBtn}>
              {S.addConversion}
            </button>
          </div>
        </ActionForm>
        <ActionForm lang={lang} path="/api/v1/wms/products" back={page} className={s.body}>
          <div className={s.formGrid}>
            <label>
              {S.sku}
              <input name="f.sku" required maxLength={64} />
            </label>
            <label>
              {S.name}
              <input name="f.name" required maxLength={200} />
            </label>
            <label>
              {a.common.type}
              <select name="f.category" defaultValue="RAW_MATERIAL">
                {Object.entries(a.category).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {S.baseUnit}
              <select name="f.baseUnit" required>
                {units.data.map((u) => (
                  <option key={u.code} value={u.code}>
                    {u.code} · {u.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {S.receiptQuality}
              <select name="f.receiptQuality" defaultValue="RELEASED">
                <option value="RELEASED">{a.quality.RELEASED}</option>
                <option value="PENDING">{a.quality.PENDING}</option>
              </select>
            </label>
            <label>
              {S.threshold}
              <input name="n.alertThreshold" type="number" min="0" step="any" />
            </label>
            <label style={{ flexDirection: "row", alignItems: "center", gap: 10, height: 44 }}>
              <input name="b.lotTracked" type="checkbox" defaultChecked style={{ width: 20, height: 20 }} />
              {S.lotTracked}
            </label>
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
