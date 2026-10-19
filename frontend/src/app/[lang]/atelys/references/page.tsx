import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { t } from "@/app/[lang]/dictionaries";
import { atelys } from "@/lib/atelys";
import { hasLocale } from "@/lib/i18n";
import { references } from "@/lib/references";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, num, one, when } from "../ui";
import { DeviceIcon } from "../devices/icons";
import d from "../devices/devices.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/references">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.references.title} · Atelys` } : {};
}

const SOURCES = [
  ["OFF", "Open Food Facts"],
  ["OBF", "Open Beauty Facts"],
  ["OPF", "Open Products Facts"],
] as const;

export default async function ReferencesPage({ params, searchParams }: PageProps<"/[lang]/atelys/references">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const R = dict.atelys.references;
  const query = await searchParams;
  const code = one(query.code)?.replace(/\s/g, "");
  const [status, scan, products] = await Promise.all([
    references.status(),
    code ? references.scan(code) : Promise.resolve(null),
    atelys.products(),
  ]);
  if (status.kind !== "ok") return <ApiDown dict={dict} kind={status.kind} />;
  const page = `/${lang}/atelys/references`;
  const result = scan?.kind === "ok" ? scan.data : null;
  const ref = result?.reference;
  const done = ref ? [ref.valid, Boolean(result?.product), ref.matches.length > 0] : [false, false, false];
  const unlinked = products.kind === "ok" ? products.data.filter((p) => !p.gtin) : [];

  return (
    <>
      <Head eyebrow={dict.atelys.nav.admin} title={R.title} lead={R.lead} />
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />

      <section className={pstyles.panel}>
        <form method="get" action={page} className={d.controls}>
          <label className={pstyles.field}>
            {R.searchLabel}
            <span className={d.inline}>
              <input
                name="code"
                defaultValue={code ?? ""}
                inputMode="numeric"
                autoComplete="off"
                autoFocus
                maxLength={20}
                placeholder={R.searchPlaceholder}
              />
              <button type="submit" className={s.flashBtn}>
                <DeviceIcon name="scan" className={d.btnIcon} />
                {R.search}
              </button>
            </span>
          </label>
        </form>
      </section>

      {ref && (
        <>
          <section className={pstyles.panel} style={{ marginTop: 18 }}>
            <ol className={d.journey} style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }} aria-label={R.title}>
              {R.steps.map((step, i) => (
                <li key={step} className={`${d.journeyStep} ${done[i] ? d.journeyDone : ""}`}>
                  <span className={d.journeyMark}>
                    <DeviceIcon name={done[i] ? "check" : i === 0 ? "error" : "info"} />
                  </span>
                  {step}
                </li>
              ))}
            </ol>
          </section>

          <div className={d.detailGrid}>
            <div className={pstyles.stack}>
              <section className={pstyles.panel}>
                <div className={pstyles.panelHead}>
                  <h2 className={d.mono}>{ref.display ?? ref.input}</h2>
                  <div className={pstyles.right}>
                    {ref.valid ? <Badge tone="ok">{R.valid}</Badge> : <Badge tone="crit">GTIN ✕</Badge>}
                  </div>
                </div>
                <div className={d.controls}>
                  {ref.valid && ref.prefix ? <p className={d.controlHint}>{R.prefix[ref.prefix]}</p> : <p className={d.controlHint}>{R.invalid}</p>}
                </div>
              </section>

              {ref.valid && (
                <section className={pstyles.panel}>
                  <div className={pstyles.panelHead}>
                    <h2>{R.open}</h2>
                  </div>
                  {ref.matches.length === 0 ? (
                    <p className={pstyles.muted} style={{ padding: "14px 18px" }}>
                      {R.openNone}
                    </p>
                  ) : (
                    <ol className={d.timeline}>
                      {ref.matches.map((m) => (
                        <li key={m.source} className={d.event}>
                          <span className={`${d.eventMark} ${d.eventOk}`}>
                            <DeviceIcon name="tag" />
                          </span>
                          <div>
                            <div className={d.eventTitle}>
                              {m.name ?? "—"} <span className={d.chip}>{m.sourceLabel}</span>
                            </div>
                            <dl className={d.cardMeta} style={{ marginTop: 6 }}>
                              <dt>{R.fields.brands}</dt>
                              <dd>{m.brands ?? "—"}</dd>
                              <dt>{R.fields.quantity}</dt>
                              <dd>{m.quantity ?? "—"}</dd>
                              <dt>{R.fields.categories}</dt>
                              <dd>{m.categories ?? "—"}</dd>
                              <dt>{R.fields.countries}</dt>
                              <dd>{m.countries ?? "—"}</dd>
                              <dt>{R.fields.updated}</dt>
                              <dd>{when(lang, m.lastModified)}</dd>
                            </dl>
                            <a href={m.url} target="_blank" rel="noopener noreferrer" className={d.eventSub}>
                              {R.seeSource} ↗
                            </a>
                          </div>
                        </li>
                      ))}
                    </ol>
                  )}
                  <p className={d.note} style={{ margin: "0 18px 18px" }}>
                    <DeviceIcon name="info" />
                    {ref.attribution}. {R.disclaimer}
                  </p>
                </section>
              )}
            </div>

            <div className={pstyles.stack}>
              {ref.valid && (
                <section className={pstyles.panel}>
                  <div className={pstyles.panelHead}>
                    <h2>{R.local}</h2>
                  </div>
                  <div className={d.controls}>
                    {result?.product ? (
                      <div className={d.adapterCard}>
                        <DeviceIcon name="check" />
                        <div>
                          <strong>{result.product.name}</strong>
                          <p className={d.mono}>{result.product.sku}</p>
                        </div>
                      </div>
                    ) : (
                      <>
                        <p className={d.controlHint}>{R.localNone}</p>
                        {unlinked.length > 0 && (
                          <ActionForm lang={lang} method="PUT" path="/api/v1/wms/products/{product}/gtin" back={`${page}?code=${ref.display}`}>
                            <input type="hidden" name="f.gtin" value={ref.display ?? ""} />
                            <label className={pstyles.field}>
                              {R.link}
                              <span className={d.inline}>
                                <select name="product" required defaultValue="">
                                  <option value="" disabled>
                                    {R.choose}
                                  </option>
                                  {unlinked.map((p) => (
                                    <option key={p.id} value={p.id}>
                                      {p.sku} · {p.name}
                                    </option>
                                  ))}
                                </select>
                                <button type="submit" className={s.ghostBtn}>
                                  {R.linkSubmit}
                                </button>
                              </span>
                            </label>
                          </ActionForm>
                        )}
                      </>
                    )}
                  </div>
                </section>
              )}
              <StatusPanel lang={lang} R={R} status={status.data} />
            </div>
          </div>
        </>
      )}

      {!ref && (
        <div style={{ marginTop: 18 }}>
          <StatusPanel lang={lang} R={R} status={status.data} />
        </div>
      )}

      {products.kind === "ok" && products.data.some((p) => p.gtin) && (
        <p className={pstyles.muted} style={{ marginTop: 14 }}>
          {products.data
            .filter((p) => p.gtin)
            .slice(0, 6)
            .map((p) => (
              <Link key={p.id} href={`${page}?code=${p.gtin!.replace(/^0/, "")}`} className={d.chip} style={{ marginInlineEnd: 6 }}>
                {p.sku}
              </Link>
            ))}
        </p>
      )}
    </>
  );
}

function StatusPanel({
  lang,
  R,
  status,
}: {
  lang: "fr" | "ar";
  R: Awaited<ReturnType<typeof getDictionary>>["atelys"]["references"];
  status: import("@/lib/references").ReferenceStatus;
}) {
  return (
    <section className={pstyles.panel}>
      <div className={pstyles.panelHead}>
        <h2>{R.status}</h2>
      </div>
      <div className={d.summary} style={{ padding: "14px 18px 0", marginBlockEnd: 0 }}>
        {SOURCES.map(([key, label]) => (
          <div key={key} className={`${d.tile} ${d["tone-total"]}`}>
            <span className={d.tileIcon}>
              <DeviceIcon name="tag" />
            </span>
            <div>
              <div className={d.tileValue}>{num(lang, status.counts[key] ?? 0, 0)}</div>
              <div className={d.tileLabel}>{label}</div>
            </div>
          </div>
        ))}
      </div>
      {status.imports.length === 0 ? (
        <p className={pstyles.muted} style={{ padding: "14px 18px" }}>
          {R.noImport}
        </p>
      ) : (
        <ol className={d.timeline}>
          <li className={d.eventSub} style={{ listStyle: "none", paddingBlock: 6 }}>
            {R.importsTitle}
          </li>
          {status.imports.slice(0, 6).map((i) => (
            <li key={i.id} className={d.event}>
              <span className={`${d.eventMark} ${i.status === "DONE" ? d.eventOk : i.status === "FAILED" ? d.eventBad : ""}`}>
                <DeviceIcon name={i.status === "DONE" ? "check" : i.status === "FAILED" ? "error" : "refresh"} />
              </span>
              <div>
                <div className={d.eventTitle}>
                  {i.source} · {R.importStatus[i.status]}
                </div>
                <div className={d.eventSub}>
                  {when(lang, i.startedAt)} ·{" "}
                  {t(R.importStats, {
                    read: num(lang, i.rowsRead, 0),
                    imported: num(lang, i.rowsImported, 0),
                    bad: num(lang, i.rowsBadCheck, 0),
                  })}
                  {i.error && ` · ${i.error}`}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
      <p className={d.controlHint} style={{ padding: "0 18px 16px" }}>
        {status.licence}
      </p>
    </section>
  );
}
