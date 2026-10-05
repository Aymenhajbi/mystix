import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { JournalPanel, type JournalRow } from "@/components/JournalPanel";
import { LogList } from "@/components/LogList";
import { INVOICE_WINDOW, listInvoices, listLogs, type InvoiceSummary, type LogEntry } from "@/lib/api";
import { formatAmount, formatDate, formatDateTime, hasLocale, intlTag, type Locale } from "@/lib/i18n";
import { cockpitStats, journal } from "@/lib/invoiceView";
import { getDictionary, t, type Dictionary } from "./dictionaries";
import styles from "./portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).cockpit.title } : {};
}

export default async function CockpitPage({ params }: PageProps<"/[lang]">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const [result, errors] = await Promise.all([listInvoices(), listLogs({ level: "ERROR", limit: 5 })]);

  return (
    <>
      <div className={styles.pageHead}>
        <h1>{dict.cockpit.title}</h1>
        <div className={styles.right}>
          <Link href={`/${lang}/invoices`} className={styles.btn}>
            {dict.cockpit.seeAll}
          </Link>
        </div>
      </div>
      {result.kind === "no-company" && <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>}
      {(result.kind === "unreachable" || result.kind === "not-found") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}
      {result.kind === "ok" && (
        <Cockpit lang={lang} dict={dict} invoices={result.data} errors={errors.kind === "ok" ? errors.data : []} />
      )}
    </>
  );
}

function Cockpit({
  lang,
  dict,
  invoices,
  errors,
}: {
  lang: Locale;
  dict: Dictionary;
  invoices: InvoiceSummary[];
  errors: LogEntry[];
}) {
  const c = dict.cockpit;
  const stats = cockpitStats(invoices);
  const percent = new Intl.NumberFormat(intlTag(lang), { maximumFractionDigits: 1, numberingSystem: "latn" });
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });
  const windowReached = invoices.length >= INVOICE_WINDOW;

  const rows: JournalRow[] = journal(invoices).map((e) => ({
    id: e.id,
    href: `/${lang}/invoices/${e.id}`,
    time: formatDateTime(lang, e.at),
    dateTime: e.at,
    severity: e.severity,
    severityLabel: dict.status[e.status],
    stage:
      e.status === "PENDING_VALIDATION" || e.status === "VALIDATION_REJECTED"
        ? c.stageValidation
        : e.status === "VALIDATED"
          ? c.stagePending
          : c.stageClearance,
    event: `${e.number} · ${
      e.status === "CLEARED"
        ? c.eventCleared
        : e.status === "CLEARANCE_REJECTED"
          ? c.eventRejected
          : e.status === "PENDING_VALIDATION"
            ? c.eventToValidate
            : e.status === "VALIDATION_REJECTED"
              ? c.eventValidationRejected
              : c.eventPending
    }`,
    detail: [e.buyerName, e.payableAmount ? formatAmount(lang, e.payableAmount, e.currency) : null]
      .filter(Boolean)
      .join(" · "),
    reference: e.reference,
    simulated: e.simulated,
    search: [e.number, e.buyerName ?? "", e.reference ?? "", dict.status[e.status]].join(" "),
  }));

  const pipeline = [
    { label: c.stepStored, value: stats.total, tone: "" },
    { label: c.stepCleared, value: stats.cleared, tone: "" },
    { label: c.stepRejected, value: stats.rejected, tone: styles.barCrit },
    { label: c.stepPending, value: stats.pending, tone: styles.barWarn },
  ];
  const toHandle = stats.rejected + stats.pending + stats.toValidate.length;

  return (
    <div className={styles.stack}>
      {stats.toValidate.length > 0 && (
        <section className={styles.warnPanel} aria-labelledby="to-validate-title">
          <h2 id="to-validate-title">{t(c.toValidateTitle, { count: stats.toValidate.length })}</h2>
          <p>{c.toValidateLead}</p>
          <ul>
            {stats.toValidate.slice(0, 5).map((i) => (
              <li key={i.id}>
                <Link href={`/${lang}/invoices/${i.id}`} className={styles.mono}>
                  {i.number}
                </Link>{" "}
                · {t(c.toValidateItem, { issued: formatDate(lang, i.issueDate), received: formatDateTime(lang, i.createdAt) })}
                {i.buyerName ? ` · ${i.buyerName}` : ""}
                {i.payableAmount ? (
                  <>
                    {" · "}
                    <bdi>{formatAmount(lang, i.payableAmount, i.currency)}</bdi>
                  </>
                ) : null}
              </li>
            ))}
          </ul>
          <Link href={`/${lang}/invoices?status=toValidate`}>{c.toValidateAll}</Link>
        </section>
      )}
      <section className={styles.strip} aria-label={c.stripLabel}>
        <div>
          <span className={styles.lbl}>{c.invoices}</span>
          <span className={styles.big}>
            {integer.format(stats.total)}
            {windowReached && <small>{t(c.window, { count: INVOICE_WINDOW })}</small>}
          </span>
          <span className={styles.sub}>
            {stats.lastActivity ? t(c.invoicesSub, { at: formatDateTime(lang, stats.lastActivity) }) : c.invoicesSubEmpty}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{c.clearance}</span>
          <span className={styles.big}>
            {stats.clearanceRate === null ? "—" : percent.format(stats.clearanceRate)}
            {stats.clearanceRate !== null && <small>%</small>}
          </span>
          <span className={styles.sub}>
            {stats.clearanceRate === null
              ? c.clearanceEmpty
              : t(c.clearanceSub, { cleared: stats.cleared, rejected: stats.rejected })}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{c.toHandle}</span>
          <span className={`${styles.big} ${toHandle > 0 ? styles.tCrit : ""}`}>{integer.format(toHandle)}</span>
          <span className={styles.sub}>
            {t(c.toHandleSub, { rejected: stats.rejected, pending: stats.pending, toValidate: stats.toValidate.length })}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{c.amount}</span>
          <span className={styles.big}>
            <bdi>
              {stats.clearedAmounts.length === 0
                ? "—"
                : stats.clearedAmounts.map((a) => formatAmount(lang, a.amount, a.currency)).join(" + ")}
            </bdi>
          </span>
          <span className={styles.sub}>{c.amountSub}</span>
        </div>
      </section>

      {invoices.length === 0 ? (
        <p className={styles.message}>{c.empty}</p>
      ) : (
        <div className={styles.gridTwo}>
          <JournalPanel
            rows={rows}
            allHref={`/${lang}/invoices`}
            labels={{
              title: c.journal,
              hint: c.journalHint,
              all: c.filterAll,
              filterLabel: c.filterLabel,
              search: c.journalSearch,
              empty: c.journalEmpty,
              foot: c.journalFoot,
              seeAll: c.seeAll,
              simulated: dict.common.simulatedBadge,
              col: {
                time: c.colTime,
                severity: c.colSeverity,
                process: c.colProcess,
                stage: c.colStage,
                event: c.colEvent,
                reference: c.colReference,
              },
              process: c.process,
              severities: { ok: dict.severity.ok, warn: dict.severity.warn, err: dict.severity.err },
            }}
          />
          <div className={styles.stack}>
            <section className={styles.panel} aria-labelledby="errors-title">
              <div className={styles.panelHead}>
                <h2 id="errors-title">{dict.logs.latestErrors}</h2>
                <span className={styles.hint}>{dict.logs.latestErrorsHint}</span>
                <div className={styles.right}>
                  <Link href={`/${lang}/logs?level=ERROR`}>{dict.logs.seeAll}</Link>
                </div>
              </div>
              {errors.length === 0 ? (
                <p className={styles.empty}>{dict.logs.noErrors}</p>
              ) : (
                <LogList entries={errors} lang={lang} dict={dict} compact />
              )}
            </section>
            <section className={styles.panel} aria-labelledby="pipeline-title">
              <div className={styles.panelHead}>
                <h2 id="pipeline-title">{c.pipeline}</h2>
                <span className={styles.hint}>{c.pipelineHint}</span>
              </div>
              <ul className={styles.pipe}>
                {pipeline.map((p) => (
                  <li key={p.label}>
                    <span>{p.label}</span>
                    <span className={styles.barTrack} aria-hidden="true">
                      <span
                        className={`${styles.barFill} ${p.tone}`}
                        style={{ width: `${stats.total ? (p.value / stats.total) * 100 : 0}%` }}
                      />
                    </span>
                    <span className={styles.n}>{integer.format(p.value)}</span>
                  </li>
                ))}
              </ul>
              <p className={styles.note}>{c.pipelineNote}</p>
            </section>
            <section className={styles.panel} aria-labelledby="send-title">
              <div className={styles.panelHead}>
                <h2 id="send-title">{c.send}</h2>
              </div>
              <div className={styles.how}>
                <Link href={`/${lang}/invoices/new`} className={`${styles.btn} ${styles.btnPrimary}`}>
                  {dict.newInvoice.title}
                </Link>
                <span>{c.sendHint}</span>
                <code className={styles.code}>
                  {"POST /api/v1/invoices\nX-Mystix-Company-Id: <id>\nContent-Type: application/json"}
                </code>
              </div>
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
