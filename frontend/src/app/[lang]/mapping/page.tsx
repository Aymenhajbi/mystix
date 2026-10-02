import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { MappingBoard } from "@/components/MappingBoard";
import { getLineage, listInvoices } from "@/lib/api";
import { formatDate, hasLocale } from "@/lib/i18n";
import { getDictionary, t } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/mapping">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).mapping.title } : {};
}

export default async function MappingPage({ params, searchParams }: PageProps<"/[lang]/mapping">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const m = dict.mapping;
  const query = await searchParams;
  const requested = Array.isArray(query.invoice) ? query.invoice[0] : query.invoice;
  const invoices = await listInvoices();

  const head = (version: string | null) => (
    <div className={styles.pageHead}>
      <div>
        <h1>
          {m.title}{" "}
          {version && <span className={`${styles.chip} ${styles.chipNeutral}`}>{t(m.badge, { version })}</span>}
        </h1>
        <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "80ch" }}>
          {m.lead}
        </p>
      </div>
    </div>
  );

  if (invoices.kind === "no-company") {
    return (
      <>
        {head(null)}
        <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>
      </>
    );
  }
  if (invoices.kind !== "ok") {
    return (
      <>
        {head(null)}
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      </>
    );
  }
  if (invoices.data.length === 0) {
    return (
      <>
        {head(null)}
        <p className={styles.message}>{m.noInvoice}</p>
      </>
    );
  }

  const selected = invoices.data.find((i) => i.id === requested) ?? invoices.data[0];
  const lineage = await getLineage(selected.id);

  return (
    <div className={styles.stack}>
      {head(lineage.kind === "ok" ? lineage.data.mappingVersion : null)}
      <div className={styles.flowBar}>
        <form className={styles.mapPicker} action={`/${lang}/mapping`}>
          <label htmlFor="mapping-invoice" className={styles.muted}>
            {m.invoice}
          </label>
          <select id="mapping-invoice" name="invoice" defaultValue={selected.id}>
            {invoices.data.map((i) => (
              <option key={i.id} value={i.id}>
                {i.number} · {i.buyerName ?? dict.common.none} · {formatDate(lang, i.issueDate)}
              </option>
            ))}
          </select>
          <button type="submit" className={`${styles.btn} ${styles.btnSm}`}>
            {m.show}
          </button>
        </form>
        <Link href={`/${lang}/invoices/${selected.id}`} className={`${styles.btn} ${styles.btnSm}`}>
          {m.openInvoice}
        </Link>
        <span className={styles.muted} style={{ fontSize: 12.5 }}>
          {m.editorSoon}
        </span>
      </div>
      {lineage.kind === "ok" ? (
        <MappingBoard key={selected.id} rows={lineage.data.rows} dict={{ mapping: m }} />
      ) : (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}
    </div>
  );
}
