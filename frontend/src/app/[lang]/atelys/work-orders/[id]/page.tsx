import type { Metadata } from "next";
import Link from "next/link";
import { randomUUID } from "node:crypto";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys, type WorkOrderStatus } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";
import pstyles from "../../../portal.module.css";
import s from "../../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, num, one, pct, Qty, WorkOrderBadge, when } from "../../ui";

/** Same transitions as the backend (WorkOrders.TRANSITIONS); IN_PROGRESS is entered by the first issue or report. */
const NEXT: Record<WorkOrderStatus, WorkOrderStatus[]> = {
  DRAFT: ["PLANNED", "RELEASED", "CANCELLED"],
  PLANNED: ["RELEASED", "DRAFT", "CANCELLED"],
  RELEASED: ["ON_HOLD", "CANCELLED"],
  IN_PROGRESS: ["ON_HOLD", "COMPLETED"],
  ON_HOLD: ["IN_PROGRESS", "CANCELLED"],
  COMPLETED: ["CLOSED", "IN_PROGRESS"],
  CLOSED: [],
  CANCELLED: [],
};

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/work-orders/[id]">): Promise<Metadata> {
  const { lang, id } = await params;
  if (!hasLocale(lang)) return {};
  const wo = await atelys.workOrder(id);
  return { title: `${wo.kind === "ok" ? wo.data.order.number : "OF"} · Atelys` };
}

export default async function WorkOrderPage({ params, searchParams }: PageProps<"/[lang]/atelys/work-orders/[id]">) {
  const { lang, id } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const w = a.workOrders;
  const query = await searchParams;
  const detail = await atelys.workOrder(id);
  if (detail.kind === "not-found") notFound();
  if (detail.kind !== "ok") return <ApiDown dict={dict} kind={detail.kind} />;
  const { order: o, materials, consumptions, reports, history, variance, bom } = detail.data;
  const [stock, products] = await Promise.all([atelys.stockLines({ siteId: o.siteId }), atelys.products("BY_PRODUCT")]);
  const components = new Set(materials.map((m) => m.componentId));
  const pickable = stock.kind === "ok"
    ? stock.data.filter((l) => components.has(l.productId) && l.quality === "RELEASED"
        && l.locationType !== "QUARANTINE" && l.locationType !== "TRANSIT")
    : [];
  const issuedLots = new Map<string, { lotId: string; label: string; unit: string; net: number }>();
  for (const c of consumptions) {
    const e = issuedLots.get(c.lotId) ?? { lotId: c.lotId, label: `${c.sku} · ${c.lotNumber}`, unit: c.unit, net: 0 };
    e.net += c.kind === "ISSUE" ? c.quantity : -c.quantity;
    issuedLots.set(c.lotId, e);
  }
  const returnable = [...issuedLots.values()].filter((x) => x.net > 0);
  const stockLocations = stock.kind === "ok"
    ? [...new Map(stock.data.filter((l) => l.locationType !== "TRANSIT").map((l) => [l.locationId, l.locationCode])).entries()]
    : [];
  const page = `/${lang}/atelys/work-orders/${o.id}`;
  const api = `/api/v1/gpao/work-orders/${o.id}`;
  const running = ["RELEASED", "IN_PROGRESS"].includes(o.status);

  return (
    <>
      <nav className={pstyles.crumbs} aria-label={dict.clients.breadcrumb}>
        <Link href={`/${lang}/atelys/work-orders`}>{w.title}</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{o.number}</span>
      </nav>
      <Head title={`${o.number} · ${o.productName}`} lead={`${o.sku} · ${w.bom} v${o.bomVersion} · ${w.output} ${o.outputLocation}${o.productionLine ? ` · ${o.productionLine}` : ""}`}>
        <WorkOrderBadge status={o.status} dict={dict} />
        {NEXT[o.status].map((target) => (
          <ActionForm key={target} lang={lang} path={`${api}/status`} back={page}>
            <input type="hidden" name="f.status" value={target} />
            <button type="submit" className={target === "CANCELLED" ? `${s.ghostBtn} ${s.dangerBtn}` : target === "RELEASED" || target === "COMPLETED" ? s.flashBtn : s.ghostBtn}>
              {w.transitions[target]}
            </button>
          </ActionForm>
        ))}
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />

      <div className={s.kpis}>
        <div className={s.kpi}>
          <span>{w.planned}</span>
          <strong>
            <Qty lang={lang} value={o.plannedQuantity} unit={o.unit} />
          </strong>
        </div>
        <div className={`${s.kpi} ${s.kpiHot}`}>
          <span>{w.good}</span>
          <strong>
            <Qty lang={lang} value={o.goodQuantity} unit={o.unit} />
          </strong>
        </div>
        <div className={s.kpi}>
          <span>{w.scrap}</span>
          <strong>
            <Qty lang={lang} value={o.scrapQuantity} unit={o.unit} />
          </strong>
        </div>
        <div className={s.kpi}>
          <span>{w.completion}</span>
          <strong>{pct(lang, variance.completion)}</strong>
        </div>
        <div className={s.kpi}>
          <span>{w.scrapRate}</span>
          <strong>{pct(lang, variance.scrapRate)}</strong>
        </div>
      </div>

      <section className={pstyles.panel}>
        <div className={pstyles.panelHead}>
          <h2>{w.materials}</h2>
          {["PLANNED", "RELEASED", "IN_PROGRESS"].includes(o.status) && (
            <div className={pstyles.right}>
              <ActionForm lang={lang} path={`${api}/reserve-materials`} back={page}>
                <button type="submit" className={s.ghostBtn}>
                  {w.reserve}
                </button>
              </ActionForm>
            </div>
          )}
        </div>
        <div className={pstyles.tableWrap}>
          <table className={pstyles.table}>
            <thead>
              <tr>
                <th>{a.common.product}</th>
                <th className={s.num}>{w.required}</th>
                <th className={s.num}>{w.reserved}</th>
                <th className={s.num}>{w.issued}</th>
                <th className={s.num}>{w.remaining}</th>
                <th className={s.num}>{w.available}</th>
              </tr>
            </thead>
            <tbody>
              {materials.map((m) => (
                <tr key={m.componentId}>
                  <td>
                    <span className={pstyles.mono}>{m.sku}</span> · {m.name}
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={m.required} unit={m.unit} />
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={m.reserved} unit={m.unit} />
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={m.issued} unit={m.unit} />
                  </td>
                  <td className={s.num}>
                    <Qty lang={lang} value={m.remaining} unit={m.unit} />
                  </td>
                  <td className={s.num}>
                    {m.available < m.remaining - m.reserved ? (
                      <Badge tone="warn">
                        <Qty lang={lang} value={m.available} unit={m.unit} />
                      </Badge>
                    ) : (
                      <Qty lang={lang} value={m.available} unit={m.unit} />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {running && (
        <div className={s.grid2} style={{ marginTop: 16 }}>
          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{w.issueTitle}</h2>
            </div>
            <ActionForm lang={lang} path={`${api}/material-consumptions`} back={page} className={s.body}>
              <input type="hidden" name="f.idempotencyKey" value={randomUUID()} />
              <div className={s.formGrid}>
                <label style={{ gridColumn: "1 / -1" }}>
                  {w.lotAndLocation}
                  <select name="p.lotId~locationId" required defaultValue="">
                    <option value="" disabled>
                      {a.common.choose}
                    </option>
                    {pickable.map((l) => (
                      <option key={`${l.lotId}|${l.locationId}`} value={`${l.lotId}|${l.locationId}`}>
                        {l.sku} · {l.lotNumber} · {l.locationCode} · {num(lang, l.quantity)} {l.unit}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  {a.common.quantity}
                  <input name="n.quantity" type="number" min="0.001" step="any" required inputMode="decimal" />
                </label>
                <label>
                  {w.justification}
                  <input name="f.justification" maxLength={500} />
                </label>
              </div>
              <div className={s.formFoot}>
                <button type="submit" className={s.flashBtn}>
                  {w.issue}
                </button>
              </div>
            </ActionForm>
          </section>

          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{w.reportTitle}</h2>
            </div>
            <ActionForm lang={lang} path={`${api}/production-reports`} back={page} className={s.body}>
              {/* A new key per form: sending the same form twice declares once. */}
              <input type="hidden" name="f.reportKey" value={randomUUID()} />
              <div className={s.formGrid}>
                <label>
                  {w.goodQty} ({o.unit})
                  <input name="n.goodQuantity" type="number" min="0" step="any" inputMode="decimal" />
                </label>
                <label>
                  {w.scrapQty} ({o.unit})
                  <input name="n.scrapQuantity" type="number" min="0" step="any" inputMode="decimal" />
                </label>
                <label>
                  {w.scrapCause}
                  <input name="f.scrapCause" maxLength={200} />
                </label>
                <label>
                  {w.operator}
                  <input name="f.operator" maxLength={120} />
                </label>
                <label>
                  {w.residueProduct}
                  <select name="r.productId" defaultValue="">
                    <option value="">{a.common.none}</option>
                    {products.kind === "ok" &&
                      products.data.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.sku} · {p.name} ({p.baseUnit})
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  {w.residueQty}
                  <input name="rn.quantity" type="number" min="0" step="any" inputMode="decimal" />
                </label>
                <label>
                  {w.residueFate}
                  <select name="r.fate" defaultValue="RECOVERED">
                    {(["RECOVERED", "PENDING", "WASTE"] as const).map((f) => (
                      <option key={f} value={f}>
                        {w.fate[f]}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  {w.destination}
                  <input name="r.destination" maxLength={40} placeholder="GRANULES" />
                </label>
              </div>
              <div className={s.formFoot}>
                <button type="submit" className={s.flashBtn}>
                  {w.report}
                </button>
              </div>
            </ActionForm>
          </section>
        </div>
      )}

      <section className={pstyles.panel} style={{ marginTop: 16 }}>
        <div className={pstyles.panelHead}>
          <h2>{w.reports}</h2>
        </div>
        {reports.length === 0 ? (
          <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
        ) : (
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.at}</th>
                  <th className={s.num}>{w.good}</th>
                  <th className={s.num}>{w.scrap}</th>
                  <th>{w.scrapCause}</th>
                  <th>{w.outputLot}</th>
                  <th>{w.residueTitle}</th>
                  <th>{w.operator}</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((r) => (
                  <tr key={r.id}>
                    <td>{when(lang, r.reportedAt)}</td>
                    <td className={s.num}>
                      <Qty lang={lang} value={r.goodQuantity} unit={o.unit} />
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={r.scrapQuantity} unit={o.unit} />
                    </td>
                    <td>{r.scrapCause ?? "—"}</td>
                    <td>
                      {r.outputLotId ? (
                        <Link href={`/${lang}/atelys/lots/${r.outputLotId}`} className={pstyles.mono}>
                          {r.outputLotNumber}
                        </Link>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td>
                      {r.residues.length === 0
                        ? "—"
                        : r.residues.map((x) => (
                            <div key={x.id}>
                              {x.sku ?? "—"} · {num(lang, x.quantity)} {x.unit} · {w.fate[x.fate].split(" (")[0]}
                              {x.lotId && (
                                <>
                                  {" · "}
                                  <Link href={`/${lang}/atelys/lots/${x.lotId}`} className={pstyles.mono}>
                                    {x.lotNumber}
                                  </Link>
                                </>
                              )}
                            </div>
                          ))}
                    </td>
                    <td>{r.operator ?? r.actor ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <div className={s.grid2} style={{ marginTop: 16 }}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{w.consumptions}</h2>
          </div>
          {consumptions.length === 0 ? (
            <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
          ) : (
            <div className={pstyles.tableWrap}>
              <table className={pstyles.table}>
                <thead>
                  <tr>
                    <th>{a.common.at}</th>
                    <th>{a.common.lot}</th>
                    <th>{a.common.location}</th>
                    <th className={s.num}>{a.common.quantity}</th>
                  </tr>
                </thead>
                <tbody>
                  {consumptions.map((c) => (
                    <tr key={c.id}>
                      <td>{when(lang, c.at)}</td>
                      <td>
                        <Link href={`/${lang}/atelys/lots/${c.lotId}`} className={pstyles.mono}>
                          {c.sku} · {c.lotNumber}
                        </Link>
                        {c.justification && <div className={pstyles.muted} style={{ fontSize: 12 }}>{c.justification}</div>}
                      </td>
                      <td>{c.locationCode}</td>
                      <td className={s.num}>
                        {c.kind === "RETURN" ? "−" : ""}
                        <Qty lang={lang} value={c.quantity} unit={c.unit} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {returnable.length > 0 && !["CLOSED", "CANCELLED"].includes(o.status) && (
            <ActionForm lang={lang} path={`${api}/material-returns`} back={page} className={s.body}>
              <input type="hidden" name="f.idempotencyKey" value={randomUUID()} />
              <div className={s.inlineForm}>
                <select name="f.lotId" required aria-label={a.common.lot} className={s.wide}>
                  {returnable.map((x) => (
                    <option key={x.lotId} value={x.lotId}>
                      {x.label} ({num(lang, x.net)} {x.unit})
                    </option>
                  ))}
                </select>
                <select name="f.locationId" required aria-label={a.common.location}>
                  {stockLocations.map(([lid, code]) => (
                    <option key={lid} value={lid}>
                      {code}
                    </option>
                  ))}
                </select>
                <input name="n.quantity" type="number" min="0.001" step="any" required aria-label={a.common.quantity} style={{ width: 110 }} />
                <button type="submit" className={s.ghostBtn}>
                  {w.return}
                </button>
              </div>
            </ActionForm>
          )}
        </section>

        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{w.variance}</h2>
          </div>
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.product}</th>
                  <th className={s.num}>{w.theoretical}</th>
                  <th className={s.num}>{w.issued}</th>
                  <th className={s.num}>{w.deviation}</th>
                </tr>
              </thead>
              <tbody>
                {variance.components.map((c) => (
                  <tr key={c.componentId}>
                    <td className={pstyles.mono}>{c.sku}</td>
                    <td className={s.num}>
                      <Qty lang={lang} value={c.theoretical} unit={c.unit} />
                    </td>
                    <td className={s.num}>
                      <Qty lang={lang} value={c.issued} unit={c.unit} />
                    </td>
                    <td className={s.num}>{pct(lang, c.deviation)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className={`${s.formula} ${s.body}`}>{w.formulas}</p>
        </section>
      </div>

      <div className={s.grid2} style={{ marginTop: 16 }}>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{a.boms.operations}</h2>
            <span className={pstyles.muted} style={{ fontSize: 12.5 }}>
              {a.boms.version} {bom.version} · {a.boms.status[bom.status]}
            </span>
          </div>
          {bom.operations.length === 0 ? (
            <p className={`${pstyles.muted} ${s.body}`}>{a.common.noData}</p>
          ) : (
            <ol className={s.body} style={{ margin: 0, paddingInlineStart: 36, display: "grid", gap: 6 }}>
              {bom.operations.map((op) => (
                <li key={op.sequence}>
                  <strong>{op.name}</strong>
                  {op.workstation && <span className={pstyles.muted}> · {op.workstation}</span>}
                  {op.standardMinutes != null && <span className={pstyles.muted}> · {num(lang, op.standardMinutes, 2)} min</span>}
                  {op.qualityCheck && <div className={pstyles.muted} style={{ fontSize: 12.5 }}>{op.qualityCheck}</div>}
                </li>
              ))}
            </ol>
          )}
        </section>
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{w.history}</h2>
          </div>
          <ul className={s.body} style={{ margin: 0, listStyle: "none", display: "grid", gap: 6, paddingInlineStart: 18 }}>
            {history.map((h, i) => (
              <li key={i} style={{ fontSize: 13.5 }}>
                <span className={pstyles.muted}>{when(lang, h.at)}</span> ·{" "}
                {h.fromStatus ? `${a.woStatus[h.fromStatus as WorkOrderStatus]} → ` : ""}
                <strong>{a.woStatus[h.toStatus as WorkOrderStatus]}</strong>
                {h.actor && <span className={pstyles.muted}> · {h.actor}</span>}
                {h.reason && <span className={pstyles.muted}> · {h.reason}</span>}
              </li>
            ))}
          </ul>
        </section>
      </div>
    </>
  );
}
