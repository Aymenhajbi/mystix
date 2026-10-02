import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { Icon } from "@/components/Icons";
import { SimulatedBadge, StatusBadge } from "@/components/StatusBadge";
import { listInvoices } from "@/lib/api";
import { formatAmount, formatDate, hasLocale } from "@/lib/i18n";
import { isStatusFilter, matchesFilter, matchesQuery, statusFilters, type StatusFilter } from "@/lib/invoiceView";
import { getDictionary, t } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/invoices">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).invoices.title } : {};
}

export default async function InvoicesPage({ params, searchParams }: PageProps<"/[lang]/invoices">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const query = await searchParams;
  const status: StatusFilter = isStatusFilter(asString(query.status)) ? (asString(query.status) as StatusFilter) : "all";
  const q = asString(query.q) ?? "";
  const result = await listInvoices();

  const href = (filter: StatusFilter) => {
    const p = new URLSearchParams();
    if (filter !== "all") p.set("status", filter);
    if (q) p.set("q", q);
    const s = p.toString();
    return `/${lang}/invoices${s ? `?${s}` : ""}`;
  };

  return (
    <>
      <div className={styles.pageHead}>
        <h1>{dict.invoices.title}</h1>
      </div>

      {result.kind === "no-company" && <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>}
      {(result.kind === "unreachable" || result.kind === "not-found") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}

      {result.kind === "ok" && (
        <section className={styles.panel} aria-labelledby="invoices-caption">
          <div className={styles.bar}>
            <nav className={styles.filters} aria-label={dict.cockpit.filterLabel}>
              {statusFilters.map((filter) => (
                <Link
                  key={filter}
                  href={href(filter)}
                  className={styles.filter}
                  aria-current={status === filter ? "true" : undefined}
                >
                  {dict.invoices.filters[filter]}{" "}
                  <span className={styles.count}>
                    · {result.data.filter((i) => matchesFilter(i, filter) && matchesQuery(i, q)).length}
                  </span>
                </Link>
              ))}
            </nav>
            <form className={styles.searchBox} action={`/${lang}/invoices`} role="search">
              {status !== "all" && <input type="hidden" name="status" value={status} />}
              <Icon name="search" size={15} />
              <input
                type="search"
                name="q"
                defaultValue={q}
                placeholder={dict.invoices.search}
                aria-label={dict.invoices.search}
              />
            </form>
          </div>
          <InvoiceTable lang={lang} dict={dict} invoices={result.data} status={status} q={q} />
        </section>
      )}
    </>
  );
}

function InvoiceTable({
  lang,
  dict,
  invoices,
  status,
  q,
}: {
  lang: "fr" | "ar";
  dict: Awaited<ReturnType<typeof getDictionary>>;
  invoices: Parameters<typeof matchesFilter>[0][];
  status: StatusFilter;
  q: string;
}) {
  const visible = invoices.filter((i) => matchesFilter(i, status) && matchesQuery(i, q));
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <caption id="invoices-caption">
          <span className={styles.captionRow}>
            <span>{dict.invoices.caption}</span>
            <span>{t(dict.invoices.count, { shown: visible.length, total: invoices.length })}</span>
          </span>
        </caption>
        <thead>
          <tr>
            <th scope="col">{dict.invoices.number}</th>
            <th scope="col">{dict.invoices.issueDate}</th>
            <th scope="col">{dict.invoices.buyer}</th>
            <th scope="col" className={styles.num}>
              {dict.invoices.amount}
            </th>
            <th scope="col">{dict.invoices.status}</th>
            <th scope="col">{dict.invoices.reference}</th>
          </tr>
        </thead>
        <tbody>
          {visible.length === 0 && (
            <tr>
              <td colSpan={6} className={styles.empty}>
                {dict.invoices.empty}
              </td>
            </tr>
          )}
          {visible.map((invoice) => (
            <tr key={invoice.id} className={styles.rowLink}>
              <th scope="row" style={{ fontWeight: 400 }}>
                <Link
                  href={`/${lang}/invoices/${invoice.id}`}
                  className={styles.strongLink}
                  aria-label={t(dict.invoices.open, { number: invoice.number })}
                >
                  <bdi className={styles.mono}>{invoice.number}</bdi>
                </Link>
              </th>
              <td>{formatDate(lang, invoice.issueDate)}</td>
              <td>{invoice.buyerName ?? dict.common.none}</td>
              <td className={styles.num}>
                <bdi>{formatAmount(lang, invoice.payableAmount, invoice.currency)}</bdi>
              </td>
              <td>
                <StatusBadge status={invoice.status} label={dict.status[invoice.status]} />
              </td>
              <td>
                {invoice.clearance.reference ? (
                  <span className={styles.ref}>{invoice.clearance.reference}</span>
                ) : (
                  <span className={styles.muted}>{dict.common.none}</span>
                )}{" "}
                {invoice.clearance.simulated && <SimulatedBadge label={dict.common.simulatedBadge} />}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function asString(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}
