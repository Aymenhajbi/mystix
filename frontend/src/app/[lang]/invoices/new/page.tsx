import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { InvoiceForm } from "@/components/InvoiceForm";
import { getCompany, listPartners, portalCompanyId } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import styles from "../../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/invoices/new">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).newInvoice.title } : {};
}

/** Today's date in the business time zone, as an input[type=date] value. */
function todayInCasablanca(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Casablanca" }).format(new Date());
}

export default async function NewInvoicePage({ params }: PageProps<"/[lang]/invoices/new">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const n = dict.newInvoice;
  const companyId = await portalCompanyId();
  if (!companyId) {
    return <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>;
  }
  const [company, partners] = await Promise.all([getCompany(companyId), listPartners(companyId)]);
  if (company.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const customers = partners.kind === "ok" ? partners.data.filter((p) => p.type === "CUSTOMER") : [];

  return (
    <div className={styles.stack}>
      <div>
        <nav className={styles.crumbs} aria-label={dict.invoice.breadcrumb}>
          <Link href={`/${lang}`}>{dict.nav.cockpit}</Link>
          <span aria-hidden="true">/</span>
          <Link href={`/${lang}/invoices`}>{dict.invoices.title}</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{n.title}</span>
        </nav>
        <div className={styles.pageHead} style={{ marginBottom: 0 }}>
          <div>
            <h1>{n.title}</h1>
            <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "85ch" }}>
              {n.lead}
            </p>
          </div>
        </div>
      </div>
      <InvoiceForm
        lang={lang}
        labels={n}
        seller={{ legalName: company.data.legalName, ice: company.data.ice, taxIdentifier: company.data.taxIdentifier }}
        customers={customers}
        today={todayInCasablanca()}
      />
    </div>
  );
}
