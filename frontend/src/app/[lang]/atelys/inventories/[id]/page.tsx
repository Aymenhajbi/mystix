import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";
import pstyles from "../../../portal.module.css";
import s from "../../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, num, one, Qty } from "../../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/inventories/[id]">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.inventories.title} · Atelys` } : {};
}

export default async function InventoryPage({ params, searchParams }: PageProps<"/[lang]/atelys/inventories/[id]">) {
  const { lang, id } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const I = a.inventories;
  const query = await searchParams;
  const count = await atelys.inventory(id);
  if (count.kind === "not-found") notFound();
  if (count.kind !== "ok") return <ApiDown dict={dict} kind={count.kind} />;
  const c = count.data;
  const page = `/${lang}/atelys/inventories/${c.id}`;
  const api = `/api/v1/wms/inventories/${c.id}`;
  const open = c.status === "OPEN";

  return (
    <>
      <nav className={pstyles.crumbs} aria-label={dict.clients.breadcrumb}>
        <Link href={`/${lang}/atelys/inventories`}>{I.title}</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{c.number}</span>
      </nav>
      <Head eyebrow={dict.atelys.nav.warehouse} title={c.number} lead={I.lead}>
        <Badge tone={c.status === "APPROVED" ? "ok" : c.status === "SUBMITTED" ? "warn" : "accent"}>{I.status[c.status]}</Badge>
        {open && (
          <ActionForm lang={lang} path={`${api}/submit`} back={page}>
            <button type="submit" className={s.ghostBtn}>
              {I.submit}
            </button>
          </ActionForm>
        )}
        {c.status === "SUBMITTED" && (
          <ActionForm lang={lang} path={`${api}/approve`} back={page}>
            <button type="submit" className={s.flashBtn}>
              {I.approve}
            </button>
          </ActionForm>
        )}
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <section className={pstyles.panel}>
        <ActionForm lang={lang} method="PUT" path={`${api}/counts`} back={page} lines="entries" list require="counted">
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.location}</th>
                  <th>{a.common.product}</th>
                  <th>{a.common.lot}</th>
                  <th className={s.num}>{I.expected}</th>
                  <th className={s.num}>{I.counted}</th>
                  <th className={s.num}>{I.delta}</th>
                  <th>{a.common.reason}</th>
                </tr>
              </thead>
              <tbody>
                {c.lines.map((l, i) => (
                  <tr key={`${l.lotId}-${l.locationId}`}>
                    <td>{l.locationCode}</td>
                    <td>
                      <span className={pstyles.mono}>{l.sku}</span> · {l.productName}
                    </td>
                    <td className={pstyles.mono}>{l.lotNumber}</td>
                    <td className={s.num}>
                      <Qty lang={lang} value={l.expected} unit={l.unit} />
                    </td>
                    <td className={s.num}>
                      {open ? (
                        <>
                          <input type="hidden" name={`l.${i}.lotId`} value={l.lotId} />
                          <input type="hidden" name={`l.${i}.locationId`} value={l.locationId} />
                          <input
                            name={`ln.${i}.counted`}
                            type="number"
                            min="0"
                            step="any"
                            defaultValue={l.counted ?? undefined}
                            aria-label={`${I.counted} ${l.lotNumber} ${l.locationCode}`}
                            style={{ width: 110, height: 40, borderRadius: 9, border: "1px solid var(--line-strong)", padding: "0 10px", background: "var(--surface)", color: "var(--text)" }}
                          />
                        </>
                      ) : (
                        <Qty lang={lang} value={l.counted} unit={l.unit} />
                      )}
                    </td>
                    <td className={s.num}>
                      {l.counted == null ? "—" : l.delta === 0 ? "0" : <Badge tone={l.delta < 0 ? "crit" : "info"}>{num(lang, l.delta)}</Badge>}
                    </td>
                    <td>
                      {open ? (
                        <input
                          name={`l.${i}.reason`}
                          defaultValue={l.reason ?? ""}
                          maxLength={500}
                          aria-label={`${a.common.reason} ${l.lotNumber}`}
                          style={{ width: "100%", minWidth: 160, height: 40, borderRadius: 9, border: "1px solid var(--line-strong)", padding: "0 10px", background: "var(--surface)", color: "var(--text)" }}
                        />
                      ) : (
                        l.reason ?? "—"
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {open && (
            <div className={`${s.formFoot} ${s.bodyFlush}`}>
              <button type="submit" className={s.flashBtn}>
                {I.saveCounts}
              </button>
            </div>
          )}
        </ActionForm>
      </section>
    </>
  );
}
