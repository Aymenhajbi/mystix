import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { FileViewer, type ViewerFile } from "@/components/FileViewer";
import { Icon } from "@/components/Icons";
import { LogList } from "@/components/LogList";
import { BackdatedBadge, SimulatedBadge, StatusBadge } from "@/components/StatusBadge";
import { fetchArtifactText, getInvoice, listLogs, type ArtifactKind } from "@/lib/api";
import { highlightLine, prettyJson } from "@/lib/highlight";
import { formatAmount, formatDate, formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { processingSteps } from "@/lib/invoiceView";
import { getDictionary, t } from "../../dictionaries";
import { decideValidationAction } from "../actions";
import styles from "../../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/invoices/[id]">): Promise<Metadata> {
  const { lang, id } = await params;
  if (!hasLocale(lang)) return {};
  const result = await getInvoice(id);
  return { title: result.kind === "ok" ? result.data.number : (await getDictionary(lang)).invoices.title };
}

const fileNames: Record<ArtifactKind, string> = {
  RAW: "request.json",
  CANONICAL: "canonical.json",
  OUT: "invoice-ubl-2.1.xml",
};

export default async function InvoicePage({ params, searchParams }: PageProps<"/[lang]/invoices/[id]">) {
  const { lang, id } = await params;
  const query = await searchParams;
  const created = query.created === "1";
  const decided = typeof query.decided === "string" ? query.decided : null;
  const decisionError = typeof query.error === "string" ? query.error : null;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const d = dict.invoice;
  const result = await getInvoice(id);

  const crumbs = (current: string) => (
    <nav className={styles.crumbs} aria-label={d.breadcrumb}>
      <Link href={`/${lang}`}>{dict.nav.cockpit}</Link>
      <span aria-hidden="true">/</span>
      <Link href={`/${lang}/invoices`}>{dict.invoices.title}</Link>
      <span aria-hidden="true">/</span>
      <bdi aria-current="page">{current}</bdi>
    </nav>
  );

  if (result.kind !== "ok") {
    const message = {
      "no-company": dict.common.devCompanyMissing,
      "not-found": d.notFound,
      unreachable: dict.common.apiUnreachable,
    }[result.kind];
    return (
      <>
        {crumbs(id)}
        <p className={styles.message} role="alert">
          {message}
        </p>
      </>
    );
  }

  const invoice = result.data;
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });
  const statusLabel = (status: string) => dict.status[status as keyof typeof dict.status] ?? status;
  const outSha = invoice.artifacts.find((a) => a.kind === "OUT")?.sha256;

  const kinds: ArtifactKind[] = ["RAW", "CANONICAL", "OUT"];
  const [texts, invoiceLogs] = await Promise.all([
    Promise.all(kinds.map((kind) => fetchArtifactText(invoice.id, kind))),
    listLogs({ invoiceId: invoice.id, limit: 100 }),
  ]);
  const files: ViewerFile[] = kinds.map((kind, i) => {
    const res = texts[i];
    const info = invoice.artifacts.find((a) => a.kind === kind);
    const text = res.kind === "ok" ? res.data : null;
    const shown = text === null ? null : kind === "OUT" ? text : prettyJson(text);
    const lines = shown === null ? null : shown.replace(/\n$/, "").split("\n");
    return {
      kind,
      label: d.fileKinds[kind],
      name: fileNames[kind],
      description: d.fileDesc[kind],
      lines: lines ? lines.map((l) => highlightLine(l, kind === "OUT" ? "xml" : "json")) : null,
      text,
      sizeLabel: t(d.bytes, { count: integer.format(info?.size ?? text?.length ?? 0) }),
      linesLabel: t(d.lines, { count: integer.format(lines?.length ?? 0) }),
    };
  });

  const stepClass = { done: "", err: styles.stepErr, warn: styles.stepWarn } as const;

  return (
    <div className={styles.stack}>
      {created && (
        <p className={styles.notice} role="status">
          {invoice.status === "PENDING_VALIDATION" ? d.createdPending : d.created}
        </p>
      )}
      {decided && (
        <p className={styles.notice} role="status">
          {decided === "approved" ? d.validation.approved : d.validation.rejected}
        </p>
      )}
      {decisionError && (
        <p className={styles.message} role="alert">
          {t(d.validation.error, { code: decisionError })}
        </p>
      )}
      {invoice.status === "PENDING_VALIDATION" && (
        <section className={styles.warnPanel} aria-labelledby="validation-title">
          <h2 id="validation-title">{d.validation.title}</h2>
          <p>{t(d.validation.lead, { issued: formatDate(lang, invoice.issueDate), received: formatDateTime(lang, invoice.createdAt) })}</p>
          <form action={decideValidationAction} className={styles.form} style={{ padding: 0 }}>
            <input type="hidden" name="lang" value={lang} />
            <input type="hidden" name="invoiceId" value={invoice.id} />
            <label className={styles.field}>
              <span>{d.validation.comment}</span>
              <textarea name="comment" maxLength={500} rows={2} />
            </label>
            <div className={styles.decision}>
              <button type="submit" name="decision" value="approve" className={`${styles.btn} ${styles.btnPrimary}`}>
                {d.validation.approve}
              </button>
              <button type="submit" name="decision" value="reject" className={`${styles.btn} ${styles.btnDanger}`}>
                {d.validation.reject}
              </button>
              <span className={styles.muted} style={{ fontSize: 12.5 }}>
                {d.validation.note}
              </span>
            </div>
          </form>
        </section>
      )}
      <section className={styles.panel}>
        <div className={styles.detailHead}>
          <div style={{ minWidth: 0 }}>
            {crumbs(invoice.number)}
            <h1>
              <bdi className={styles.mono}>{invoice.number}</bdi>
              <StatusBadge status={invoice.status} label={statusLabel(invoice.status)} />
              {invoice.clearance.simulated && <SimulatedBadge label={dict.common.simulatedBadge} />}
              {invoice.backdated && <BackdatedBadge label={d.backdated} />}
            </h1>
            {invoice.backdated && (
              <p className={styles.formNote} style={{ margin: "4px 0 0" }}>
                {t(d.backdatedNote, { issued: formatDate(lang, invoice.issueDate), received: formatDateTime(lang, invoice.createdAt) })}
              </p>
            )}
            <div className={styles.meta}>
              <span>{invoice.buyerName ?? dict.common.none}</span>
              <span>
                <b>
                  <bdi>{formatAmount(lang, invoice.payableAmount, invoice.currency)}</bdi>
                </b>
              </span>
              <span>{t(d.formats, { version: invoice.canonicalVersion })}</span>
            </div>
            <div className={styles.meta}>
              <span className={styles.mono}>{invoice.id}</span>
              {outSha && <span className={styles.mono}>sha256 {outSha.slice(0, 12)}…</span>}
              <span className={styles.mono}>EN 16931 · UBL 2.1</span>
            </div>
          </div>
          <div className={styles.actions}>
            <Link href={`/${lang}/mapping?invoice=${invoice.id}`} className={styles.btn}>
              <Icon name="mapping" size={16} />
              {dict.mapping.seeLineage}
            </Link>
            <a href={`/api/invoices/${invoice.id}/ubl`} className={`${styles.btn} ${styles.btnPrimary}`} download>
              <Icon name="download" size={16} />
              {d.download}
            </a>
          </div>
        </div>
        <ol className={styles.steps} aria-label={d.steps}>
          {processingSteps(invoice).map((s) => (
            <li key={s.key} className={`${styles.step} ${stepClass[s.state]}`}>
              {d.step[s.key]}
            </li>
          ))}
        </ol>
      </section>

      <div className={styles.gridTwo}>
        <FileViewer
          files={files}
          initial="OUT"
          labels={{
            title: d.files,
            copy: d.copy,
            copied: d.copied,
            copyFailed: d.copyFailed,
            unavailable: d.fileUnavailable,
          }}
        />

        <div className={styles.stack}>
          <section className={styles.panel} aria-labelledby="summary-title">
            <div className={styles.panelHead}>
              <h2 id="summary-title">{d.summary}</h2>
            </div>
            <dl className={styles.facts}>
              <dt>{d.issueDate}</dt>
              <dd>{formatDate(lang, invoice.issueDate)}</dd>
              <dt>{d.buyer}</dt>
              <dd>{invoice.buyerName ?? dict.common.none}</dd>
              <dt>{d.amount}</dt>
              <dd>
                <bdi>{formatAmount(lang, invoice.payableAmount, invoice.currency)}</bdi>
              </dd>
              <dt>{d.storedAt}</dt>
              <dd>{formatDateTime(lang, invoice.createdAt)}</dd>
            </dl>
          </section>

          <section className={styles.panel} aria-labelledby="clearance-title">
            <div className={styles.panelHead}>
              <h2 id="clearance-title">{d.clearance}</h2>
              {invoice.clearance.simulated && <SimulatedBadge label={dict.common.simulatedBadge} />}
            </div>
            <dl className={styles.facts}>
              <dt>{d.reference}</dt>
              <dd className={styles.ref}>{invoice.clearance.reference ?? d.noReference}</dd>
              <dt>{d.answeredAt}</dt>
              <dd>{invoice.clearance.at ? formatDateTime(lang, invoice.clearance.at) : dict.common.none}</dd>
            </dl>
            {invoice.clearance.simulated && <p className={styles.sideNote}>{d.clearanceNote}</p>}
          </section>

          <section className={styles.panel} aria-labelledby="history-title">
            <div className={styles.panelHead}>
              <h2 id="history-title">{d.history}</h2>
            </div>
            <ol className={styles.events}>
              {invoice.history.map((event, index) => (
                <li key={`${event.occurredAt}-${index}`}>
                  <time className={styles.time} dateTime={event.occurredAt}>
                    {formatDateTime(lang, event.occurredAt)}
                  </time>
                  <span>
                    <StatusBadge status={event.status} label={statusLabel(event.status)} />
                  </span>
                  {event.detail && (
                    <span className={styles.detail} lang="en">
                      {event.detail}
                    </span>
                  )}
                </li>
              ))}
            </ol>
          </section>
        </div>
      </div>

      {invoiceLogs.kind === "ok" && invoiceLogs.data.length > 0 && (
        <section className={styles.panel} aria-labelledby="invoice-logs-title">
          <div className={styles.panelHead}>
            <h2 id="invoice-logs-title">{dict.logs.invoiceLogs}</h2>
            <div className={styles.right}>
              <Link href={`/${lang}/logs?q=${encodeURIComponent(invoice.number)}`}>{dict.logs.seeAll}</Link>
            </div>
          </div>
          <LogList entries={invoiceLogs.data} lang={lang} dict={dict} />
        </section>
      )}
    </div>
  );
}
