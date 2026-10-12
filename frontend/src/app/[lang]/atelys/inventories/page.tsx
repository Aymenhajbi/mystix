import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, one, when } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/inventories">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.inventories.title} · Atelys` } : {};
}

export default async function InventoriesPage({ params, searchParams }: PageProps<"/[lang]/atelys/inventories">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const I = a.inventories;
  const query = await searchParams;
  const [list, warehouses, sites] = await Promise.all([atelys.inventories(), atelys.warehouses(), atelys.sites()]);
  if (list.kind !== "ok" || warehouses.kind !== "ok" || sites.kind !== "ok") return <ApiDown dict={dict} kind={list.kind} />;
  const page = `/${lang}/atelys/inventories`;
  const wh = (id: string) => {
    const w = warehouses.data.find((x) => x.id === id);
    return w ? `${sites.data.find((x) => x.id === w.siteId)?.code ?? ""} · ${w.code} · ${w.name}` : id;
  };
  const tone = { OPEN: "accent", SUBMITTED: "warn", APPROVED: "ok" } as const;

  return (
    <>
      <Head eyebrow={dict.atelys.nav.warehouse} title={I.title} lead={I.lead} />
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />
      <section className={pstyles.panel}>
        <div className={pstyles.panelHead}>
          <h2>{I.new}</h2>
        </div>
        <ActionForm lang={lang} path="/api/v1/wms/inventories" back={page} next={`${page}/{id}`} className={s.body}>
          <div className={s.inlineForm}>
            <select name="f.warehouseId" required aria-label={a.common.warehouse} className={s.wide}>
              {warehouses.data.map((w) => (
                <option key={w.id} value={w.id}>
                  {wh(w.id)}
                </option>
              ))}
            </select>
            <button type="submit" className={s.flashBtn}>
              {I.new}
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
                  <th>{a.common.warehouse}</th>
                  <th>{a.common.status}</th>
                  <th>{a.common.date}</th>
                </tr>
              </thead>
              <tbody>
                {list.data.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <Link href={`${page}/${c.id}`} className={pstyles.mono}>
                        {c.number}
                      </Link>
                    </td>
                    <td>{wh(c.warehouseId)}</td>
                    <td>
                      <Badge tone={tone[c.status]}>{I.status[c.status]}</Badge>
                    </td>
                    <td>{when(lang, c.approvedAt ?? c.submittedAt ?? c.createdAt)}</td>
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
