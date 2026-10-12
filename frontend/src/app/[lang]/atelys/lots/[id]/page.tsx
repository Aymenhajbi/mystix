import type { Metadata } from "next";
import Link from "next/link";
import QRCode from "qrcode";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys, type GenealogyNode } from "@/lib/atelys";
import { hasLocale, type Locale } from "@/lib/i18n";
import { getDictionary, type Dictionary } from "../../../dictionaries";
import pstyles from "../../../portal.module.css";
import s from "../../atelys.module.css";
import { ActionForm, ApiDown, Head, Notice, one, Qty, QualityBadge, when } from "../../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/lots/[id]">): Promise<Metadata> {
  const { lang, id } = await params;
  if (!hasLocale(lang)) return {};
  const lot = await atelys.lot(id);
  return { title: `${lot.kind === "ok" ? lot.data.lot.lotNumber : "Lot"} · Atelys` };
}

function Tree({ nodes, lang, dict }: { nodes: GenealogyNode[]; lang: Locale; dict: Dictionary }) {
  if (nodes.length === 0) return <p className={pstyles.muted}>{dict.atelys.common.noData}</p>;
  return (
    <div className={s.tree}>
      {nodes.map((n) => (
        <Link key={n.lotId} href={`/${lang}/atelys/lots/${n.lotId}`} className={s.treeItem} style={{ marginInlineStart: (n.depth - 1) * 18 }}>
          <span className={s.depth}>N{n.depth}</span>
          <span className={pstyles.mono}>{n.lotNumber}</span>
          <span>
            {n.sku} · {n.productName}
          </span>
          <QualityBadge quality={n.quality} dict={dict} />
        </Link>
      ))}
    </div>
  );
}

export default async function LotPage({ params, searchParams }: PageProps<"/[lang]/atelys/lots/[id]">) {
  const { lang, id } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const L = a.lot;
  const query = await searchParams;
  const view = await atelys.lot(id);
  if (view.kind === "not-found") notFound();
  if (view.kind !== "ok") return <ApiDown dict={dict} kind={view.kind} />;
  const { lot, stock, availability: av, movements, genealogy, audit } = view.data;
  const page = `/${lang}/atelys/lots/${lot.id}`;
  const qr = await QRCode.toString(lot.publicCode, { type: "svg", margin: 0, errorCorrectionLevel: "M" });
  const untracked = lot.origin === "UNTRACKED";
  const attributes = Object.entries(lot.attributes ?? {});

  return (
    <>
      <Head eyebrow={dict.atelys.nav.warehouse} title={`${L.title} ${lot.lotNumber}`} lead={`${lot.sku} · ${lot.productName} · ${L.origin} : ${L.origins[lot.origin as keyof typeof L.origins] ?? lot.origin}`}>
        <QualityBadge quality={lot.qualityStatus} dict={dict} />
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />

      <div className={s.five} style={{ marginBottom: 16 }}>
        {(["physical", "blocked", "reserved", "available", "inTransit"] as const).map((k) => (
          <div key={k} className={k === "available" ? s.fiveAvailable : undefined}>
            <span>{a.stockCols[k]}</span>
            <strong>
              <Qty lang={lang} value={av[k]} unit={lot.unit} />
            </strong>
          </div>
        ))}
      </div>

      <div className={s.grid2}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{L.qr}</h2>
          </div>
          <div className={`${s.body} ${s.qr}`}>
            <div className={s.qrBox} role="img" aria-label={`${L.qr} ${lot.lotNumber}`} dangerouslySetInnerHTML={{ __html: qr }} />
            <div style={{ display: "grid", gap: 6, fontSize: 13.5 }}>
              <span className={pstyles.mono} style={{ fontSize: 15 }}>{lot.publicCode}</span>
              <span>
                <strong>{lot.lotNumber}</strong> · {lot.sku}
              </span>
              {lot.supplier && (
                <span>
                  {L.supplier} : {lot.supplier}
                  {lot.supplierLot && ` · ${L.supplierLot} ${lot.supplierLot}`}
                </span>
              )}
              {lot.workOrderId && (
                <Link href={`/${lang}/atelys/work-orders/${lot.workOrderId}`}>{a.nav.workOrders}</Link>
              )}
              <span className={pstyles.muted} style={{ fontSize: 12 }}>{L.qrNote}</span>
            </div>
          </div>
          {attributes.length > 0 && (
            <dl className={s.bodyFlush} style={{ margin: 0, display: "grid", gridTemplateColumns: "auto 1fr", gap: "4px 14px", fontSize: 13.5 }}>
              {attributes.map(([k, v]) => (
                <div key={k} style={{ display: "contents" }}>
                  <dt className={pstyles.muted}>{k}</dt>
                  <dd style={{ margin: 0 }}>{String(v)}</dd>
                </div>
              ))}
            </dl>
          )}
        </section>

        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{a.lot.title} · {a.common.status}</h2>
          </div>
          {untracked ? (
            <p className={`${pstyles.muted} ${s.body}`}>{L.origins.UNTRACKED}</p>
          ) : (
            <ActionForm lang={lang} path={`/api/v1/wms/lots/${lot.id}/quality`} back={page} className={s.body}>
              <div className={s.formGrid}>
                <label style={{ gridColumn: "1 / -1" }}>
                  {a.common.reason}
                  <input name="f.reason" maxLength={500} placeholder={a.common.optional} />
                </label>
              </div>
              <div className={s.formFoot}>
                {lot.qualityStatus !== "RELEASED" && (
                  <button type="submit" name="f.status" value="RELEASED" className={s.flashBtn}>
                    {L.release}
                  </button>
                )}
                {lot.qualityStatus !== "BLOCKED" && (
                  <button type="submit" name="f.status" value="BLOCKED" className={s.ghostBtn}>
                    {L.block}
                  </button>
                )}
                {lot.qualityStatus !== "REJECTED" && (
                  <button type="submit" name="f.status" value="REJECTED" className={`${s.ghostBtn} ${s.dangerBtn}`}>
                    {L.reject}
                  </button>
                )}
              </div>
            </ActionForm>
          )}
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <caption className={pstyles.srOnly}>{L.stockAt}</caption>
              <thead>
                <tr>
                  <th>{a.common.site}</th>
                  <th>{a.common.location}</th>
                  <th className={s.num}>{a.common.quantity}</th>
                </tr>
              </thead>
              <tbody>
                {stock.map((l) => (
                  <tr key={l.locationId}>
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
        </section>
      </div>

      <div className={s.grid2} style={{ marginTop: 16 }}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{L.upstream}</h2>
          </div>
          <div className={s.body}>
            <Tree nodes={genealogy.upstream} lang={lang} dict={dict} />
          </div>
        </section>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{L.downstream}</h2>
          </div>
          <div className={s.body}>
            <Tree nodes={genealogy.downstream} lang={lang} dict={dict} />
          </div>
        </section>
      </div>

      <section className={pstyles.panel} style={{ marginTop: 16 }}>
        <div className={pstyles.panelHead}>
          <h2>{L.movements}</h2>
        </div>
        <div className={pstyles.tableWrap}>
          <table className={pstyles.table}>
            <thead>
              <tr>
                <th>{a.common.at}</th>
                <th>{a.common.type}</th>
                <th>{a.common.from}</th>
                <th>{a.common.to}</th>
                <th className={s.num}>{a.common.quantity}</th>
                <th>{a.common.actor}</th>
                <th>{a.common.reason}</th>
              </tr>
            </thead>
            <tbody>
              {movements.map((m) => (
                <tr key={m.id}>
                  <td>{when(lang, m.occurredAt)}</td>
                  <td>{a.movementType[m.type as keyof typeof a.movementType] ?? m.type}</td>
                  <td>{m.fromLocation ?? "—"}</td>
                  <td>{m.toLocation ?? "—"}</td>
                  <td className={s.num}>
                    <Qty lang={lang} value={m.quantity} unit={m.unit} />
                  </td>
                  <td>{m.actor ?? "—"}</td>
                  <td>{m.reason ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {audit.length > 0 && (
        <section className={pstyles.panel} style={{ marginTop: 16 }}>
          <div className={pstyles.panelHead}>
            <h2>{L.audit}</h2>
          </div>
          <ul className={s.body} style={{ margin: 0, listStyle: "none", display: "grid", gap: 6, paddingInlineStart: 18 }}>
            {audit.map((e) => (
              <li key={e.id} style={{ fontSize: 13.5 }}>
                <span className={pstyles.muted}>{when(lang, e.at)}</span> · <strong>{e.action}</strong> · {e.actor ?? "—"}
                {e.reason && <span className={pstyles.muted}> · {e.reason}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
