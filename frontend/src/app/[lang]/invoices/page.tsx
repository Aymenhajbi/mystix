import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { SimulatedBadge, StatusBadge } from "@/components/StatusBadge";
import { listInvoices } from "@/lib/api";
import { formatAmount, formatDate, hasLocale } from "@/lib/i18n";
import { getDictionary, t } from "../dictionaries";
import styles from "../portal.module.css";

export default async function InvoicesPage({ params }: PageProps<"/[lang]/invoices">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const result = await listInvoices();

  return (
    <main className={styles.main}>
      <h1 className={styles.title}>{dict.invoices.title}</h1>

      {result.kind === "no-company" && <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>}
      {(result.kind === "unreachable" || result.kind === "not-found") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}

      {result.kind === "ok" && result.data.length === 0 && <p className={styles.muted}>{dict.invoices.empty}</p>}

      {result.kind === "ok" && result.data.length > 0 && (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <caption>{dict.invoices.caption}</caption>
            <thead>
              <tr>
                <th scope="col">{dict.invoices.number}</th>
                <th scope="col">{dict.invoices.issueDate}</th>
                <th scope="col">{dict.invoices.buyer}</th>
                <th scope="col" className={styles.amount}>{dict.invoices.amount}</th>
                <th scope="col">{dict.invoices.status}</th>
              </tr>
            </thead>
            <tbody>
              {result.data.map((invoice) => (
                <tr key={invoice.id}>
                  <th scope="row">
                    <Link
                      href={`/${lang}/invoices/${invoice.id}`}
                      aria-label={t(dict.invoices.open, { number: invoice.number })}
                    >
                      <bdi>{invoice.number}</bdi>
                    </Link>
                  </th>
                  <td>{formatDate(lang, invoice.issueDate)}</td>
                  <td>{invoice.buyerName ?? "—"}</td>
                  <td className={styles.amount}>
                    <bdi>{formatAmount(lang, invoice.payableAmount, invoice.currency)}</bdi>
                  </td>
                  <td>
                    <span className={styles.badges}>
                      <StatusBadge status={invoice.status} label={dict.status[invoice.status]} />
                      {invoice.clearance.simulated && <SimulatedBadge label={dict.common.simulatedBadge} />}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}
