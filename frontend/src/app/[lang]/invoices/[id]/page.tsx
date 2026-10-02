import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { SimulatedBadge, StatusBadge } from "@/components/StatusBadge";
import { getInvoice } from "@/lib/api";
import { formatAmount, formatDate, formatDateTime, hasLocale } from "@/lib/i18n";
import { getDictionary, t } from "../../dictionaries";
import styles from "../../portal.module.css";

export default async function InvoicePage({ params }: PageProps<"/[lang]/invoices/[id]">) {
  const { lang, id } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const result = await getInvoice(id);
  const back = (
    <p>
      <Link href={`/${lang}/invoices`}>{dict.common.back}</Link>
    </p>
  );

  if (result.kind !== "ok") {
    const message = {
      "no-company": dict.common.devCompanyMissing,
      "not-found": dict.invoice.notFound,
      unreachable: dict.common.apiUnreachable,
    }[result.kind];
    return (
      <main className={styles.main}>
        {back}
        <p className={styles.message} role="alert">{message}</p>
      </main>
    );
  }

  const invoice = result.data;
  const statusLabel = (status: string) => dict.status[status as keyof typeof dict.status] ?? status;

  return (
    <main className={styles.main}>
      {back}
      <h1 className={styles.title}>
        {t(dict.invoice.title, { number: "" })}
        <bdi>{invoice.number}</bdi>
      </h1>

      <section className={styles.card} aria-labelledby="summary">
        <h2 id="summary">{dict.invoice.summary}</h2>
        <dl className={styles.facts}>
          <dt>{dict.invoices.issueDate}</dt>
          <dd>{formatDate(lang, invoice.issueDate)}</dd>
          <dt>{dict.invoices.buyer}</dt>
          <dd>{invoice.buyerName ?? "—"}</dd>
          <dt>{dict.invoices.amount}</dt>
          <dd>
            <bdi>{formatAmount(lang, invoice.payableAmount, invoice.currency)}</bdi>
          </dd>
          <dt>{dict.invoices.status}</dt>
          <dd>
            <StatusBadge status={invoice.status} label={statusLabel(invoice.status)} />
          </dd>
          <dt>{dict.invoice.canonicalVersion}</dt>
          <dd>{invoice.canonicalVersion}</dd>
        </dl>
      </section>

      <section className={styles.card} aria-labelledby="clearance">
        <h2 id="clearance">
          {dict.invoice.clearance}{" "}
          {invoice.clearance.simulated && <SimulatedBadge label={dict.common.simulatedBadge} />}
        </h2>
        <dl className={styles.facts}>
          <dt>{dict.invoice.reference}</dt>
          <dd className={styles.mono}>{invoice.clearance.reference ?? dict.invoice.noReference}</dd>
          <dt>{dict.invoice.clearedAt}</dt>
          <dd>{invoice.clearance.at ? formatDateTime(lang, invoice.clearance.at) : "—"}</dd>
        </dl>
      </section>

      <section className={styles.card} aria-labelledby="history">
        <h2 id="history">{dict.invoice.history}</h2>
        <ol className={styles.timeline}>
          {invoice.history.map((event, index) => (
            <li key={`${event.occurredAt}-${index}`}>
              <StatusBadge status={event.status} label={statusLabel(event.status)} />{" "}
              <time dateTime={event.occurredAt} className={styles.muted}>
                {formatDateTime(lang, event.occurredAt)}
              </time>
              {event.detail && (
                <div className={styles.muted} lang="en">
                  {event.detail}
                </div>
              )}
            </li>
          ))}
        </ol>
      </section>

      <section className={styles.card} aria-labelledby="artifacts">
        <h2 id="artifacts">{dict.invoice.artifacts}</h2>
        <ul>
          {invoice.artifacts.map((artifact) => (
            <li key={artifact.kind}>
              {dict.invoice.kind[artifact.kind]} — {t(dict.invoice.size, { size: artifact.size })}{" "}
              <span className={styles.mono}>SHA-256 {artifact.sha256.slice(0, 16)}…</span>
            </li>
          ))}
        </ul>
        <a href={`/api/invoices/${invoice.id}/ubl`} className={styles.button} download>
          {dict.invoice.downloadUbl}
        </a>
      </section>
    </main>
  );
}
