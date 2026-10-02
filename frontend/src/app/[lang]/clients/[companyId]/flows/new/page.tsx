import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { DirectionBadge } from "@/components/FlowChips";
import { getCompany, getFlowCatalog, isUuid, listPartners, type CatalogOption, type FlowDirection } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary, t, type Dictionary } from "../../../../dictionaries";
import styles from "../../../../portal.module.css";
import { createFlowAction } from "../../../actions";

type Params = PageProps<"/[lang]/clients/[companyId]/flows/new">;

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).clients.create.title } : {};
}

/** Planned options stay selectable: the flow is then declared, and cannot be activated before its lot. */
function OptionSelect({
  name,
  label,
  options,
  labels,
  dict,
}: {
  name: string;
  label: string;
  options: CatalogOption[];
  labels: Record<string, string>;
  dict: Dictionary;
}) {
  const first = (options.find((o) => o.available) ?? options[0])?.code;
  return (
    <label className={styles.field}>
      <span>{label}</span>
      <select name={name} defaultValue={first} required>
        {options.map((o) => (
          <option key={o.code} value={o.code}>
            {labels[o.code] ?? o.code}
            {o.available ? "" : ` (${t(dict.clients.planned, { lot: o.lot ?? "?" })})`}
          </option>
        ))}
      </select>
    </label>
  );
}

export default async function NewFlowPage({ params, searchParams }: Params) {
  const { lang, companyId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.clients;
  const query = await searchParams;
  const error = Array.isArray(query.error) ? query.error[0] : query.error;
  const direction: FlowDirection = query.direction === "IN" ? "IN" : "OUT";
  const [company, catalog, partners] = await Promise.all([
    getCompany(companyId),
    getFlowCatalog(companyId),
    listPartners(companyId),
  ]);
  if (company.kind === "not-found") notFound();
  if (company.kind !== "ok" || catalog.kind !== "ok" || partners.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const errorText = error ? (c.errors[error as keyof typeof c.errors] ?? t(c.errors.default, { code: error })) : null;
  const options = direction === "IN" ? catalog.data.in : catalog.data.out;
  const mappings = catalog.data.mappings.filter((m) => m.direction === direction);
  const base = `/${lang}/clients/${companyId}/flows/new`;

  return (
    <div className={styles.stack}>
      <div>
        <nav className={styles.crumbs} aria-label={c.breadcrumb}>
          <Link href={`/${lang}/clients`}>{c.title}</Link>
          <span aria-hidden="true">/</span>
          <Link href={`/${lang}/clients/${companyId}`}>{company.data.legalName}</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{c.create.title}</span>
        </nav>
        <div className={styles.pageHead} style={{ marginBottom: 0 }}>
          <div>
            <h1>{c.create.title}</h1>
            <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "80ch" }}>
              {c.create.lead}
            </p>
          </div>
        </div>
      </div>
      {errorText && (
        <p className={styles.message} role="alert">
          {errorText}
        </p>
      )}
      <nav className={styles.dirTabs} aria-label={c.create.direction}>
        {(["OUT", "IN"] as const).map((d) => (
          <Link key={d} href={`${base}?direction=${d}`} aria-current={d === direction ? "page" : undefined}>
            <DirectionBadge direction={d} dict={dict} /> {c.directionTitle[d]}
          </Link>
        ))}
      </nav>
      <section className={styles.panel}>
        <form action={createFlowAction} className={`${styles.form} ${styles.formWide}`}>
          <input type="hidden" name="lang" value={lang} />
          <input type="hidden" name="companyId" value={companyId} />
          <input type="hidden" name="direction" value={direction} />
          <p className={styles.formNote} style={{ margin: 0 }}>
            {c.directionHint[direction]}
          </p>
          <div className={styles.formRow}>
            <label className={styles.field}>
              <span>{c.create.name}</span>
              <input name="name" required maxLength={120} placeholder={c.create.namePlaceholder} />
            </label>
            <label className={styles.field}>
              <span>{c.create.document}</span>
              <select name="documentType" defaultValue="INVOICE" disabled>
                <option value="INVOICE">{c.create.invoice}</option>
              </select>
            </label>
          </div>
          <label className={styles.field}>
            <span>{c.partner.label}</span>
            <select name="partnerId" defaultValue="">
              <option value="">{c.partner.all}</option>
              {partners.data.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} · {c.partner.types[p.type]}
                </option>
              ))}
            </select>
          </label>
          {partners.data.length === 0 && (
            <p className={styles.formNote} style={{ margin: 0 }}>
              <Link href={`/${lang}/clients/${companyId}#partners`}>{c.partner.addFirst}</Link>
            </p>
          )}
          <div className={styles.formRow}>
            <OptionSelect
              name="sourceChannel"
              label={c.create.sourceChannel}
              options={options.sourceChannels}
              labels={c.channels}
              dict={dict}
            />
            <OptionSelect
              name="sourceFormat"
              label={c.create.sourceFormat}
              options={options.sourceFormats}
              labels={c.formats}
              dict={dict}
            />
          </div>
          <div className={styles.formRow}>
            <OptionSelect
              name="targetFormat"
              label={c.create.targetFormat}
              options={options.targetFormats}
              labels={c.formats}
              dict={dict}
            />
            <OptionSelect
              name="targetChannel"
              label={c.create.targetChannel}
              options={options.targetChannels}
              labels={c.channels}
              dict={dict}
            />
          </div>
          <p className={styles.formNote}>
            {mappings.length > 0 ? (
              <>
                {c.create.mappingAuto} :{" "}
                {mappings.map((m) => (
                  <span key={m.id} className={styles.mono}>
                    {c.formats[m.sourceFormat as keyof typeof c.formats] ?? m.sourceFormat} →{" "}
                    {c.formats[m.targetFormat as keyof typeof c.formats] ?? m.targetFormat} ({m.id} {m.version})
                  </span>
                ))}
                . {c.create.declaredNote}
              </>
            ) : (
              c.create.noMappingYet
            )}
          </p>
          <div className={styles.formActions}>
            <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`}>
              {c.create.submit}
            </button>
            <Link href={`/${lang}/clients/${companyId}`} className={styles.btn}>
              {c.create.cancel}
            </Link>
          </div>
        </form>
      </section>
    </div>
  );
}
