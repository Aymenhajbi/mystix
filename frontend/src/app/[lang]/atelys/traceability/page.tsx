import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound, redirect } from "next/navigation";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ApiDown, Head, one, QualityBadge, when } from "../ui";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/traceability">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.trace.title} · Atelys` } : {};
}

/** Scan-first search: a QR public code opens the lot at once; a lot number may match several products. */
export default async function TraceabilityPage({ params, searchParams }: PageProps<"/[lang]/atelys/traceability">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const a = dict.atelys;
  const T = a.trace;
  const code = one((await searchParams).code);
  const found = code ? await atelys.scan(code) : null;
  if (found && found.kind !== "ok") return <ApiDown dict={dict} kind={found.kind} />;
  const lots = found?.kind === "ok" ? found.data : [];
  if (code && lots.length === 1) redirect(`/${lang}/atelys/lots/${lots[0].id}`);
  const recent = code ? null : await atelys.lots();

  return (
    <>
      <Head title={T.title} lead={T.lead} />
      <form className={s.scan} role="search">
        <input
          name="code"
          defaultValue={code}
          placeholder="MX… / L2026…"
          aria-label={T.scanLabel}
          autoFocus
          autoComplete="off"
          spellCheck={false}
          dir="ltr"
        />
        <button type="submit" className={s.flashBtn} style={{ height: 56 }}>
          {a.common.search}
        </button>
      </form>
      <p className={pstyles.muted} style={{ fontSize: 13, margin: "8px 0 18px" }}>{T.scanHint}</p>
      {code && lots.length === 0 && <p className={pstyles.muted}>{T.noResult}</p>}
      {(lots.length > 1 || (recent?.kind === "ok" && recent.data.length > 0)) && (
        <section className={pstyles.panel}>
          <div className={pstyles.panelHead}>
            <h2>{lots.length > 1 ? T.results : a.nav.stock}</h2>
          </div>
          <div className={pstyles.tableWrap}>
            <table className={pstyles.table}>
              <thead>
                <tr>
                  <th>{a.common.lot}</th>
                  <th>{a.common.product}</th>
                  <th>{a.lot.title}</th>
                  <th>{a.lot.origin}</th>
                  <th>{a.common.date}</th>
                </tr>
              </thead>
              <tbody>
                {(lots.length > 1 ? lots : recent?.kind === "ok" ? recent.data : []).map((l) => (
                  <tr key={l.id}>
                    <td>
                      <Link href={`/${lang}/atelys/lots/${l.id}`} className={pstyles.mono}>
                        {l.lotNumber}
                      </Link>
                    </td>
                    <td>
                      <span className={pstyles.mono}>{l.sku}</span> · {l.productName}
                    </td>
                    <td>
                      <QualityBadge quality={l.qualityStatus} dict={dict} />
                    </td>
                    <td>{a.lot.origins[l.origin as keyof typeof a.lot.origins] ?? l.origin}</td>
                    <td>{when(lang, l.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  );
}
