import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { FlowRoute, FlowStatusBadge } from "@/components/FlowChips";
import { MappingBoard } from "@/components/MappingBoard";
import { SimulatedBadge, StatusBadge } from "@/components/StatusBadge";
import {
  getCompany,
  getFlow,
  getLineageFor,
  getMappingSpec,
  isUuid,
  listFlowInvoices,
  specRows,
  type LineageRow,
} from "@/lib/api";
import { formatAmount, formatDateTime, hasLocale } from "@/lib/i18n";
import { getDictionary, t } from "../../../../dictionaries";
import styles from "../../../../portal.module.css";
import { updateFlowAction } from "../../../actions";

type Params = PageProps<"/[lang]/clients/[companyId]/flows/[flowId]">;

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { lang, companyId, flowId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId) || !isUuid(flowId)) return {};
  const flow = await getFlow(companyId, flowId);
  return { title: flow.kind === "ok" ? flow.data.name : (await getDictionary(lang)).clients.flows };
}

export default async function FlowPage({ params, searchParams }: Params) {
  const { lang, companyId, flowId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId) || !isUuid(flowId)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.clients;
  const query = await searchParams;
  const error = Array.isArray(query.error) ? query.error[0] : query.error;
  const saved = query.saved === "1" || query.created === "1";

  const [company, flow, invoices] = await Promise.all([
    getCompany(companyId),
    getFlow(companyId, flowId),
    listFlowInvoices(companyId, flowId),
  ]);
  if (flow.kind === "not-found" || company.kind === "not-found") notFound();
  if (flow.kind !== "ok" || company.kind !== "ok" || invoices.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }

  const latest = invoices.data[0];
  let rows: LineageRow[] = [];
  if (latest) {
    const lineage = await getLineageFor(companyId, latest.id);
    if (lineage.kind === "ok") rows = lineage.data.rows;
  } else {
    const spec = await getMappingSpec(companyId);
    if (spec.kind === "ok") rows = specRows(spec.data);
  }
  const errorText = error ? (c.errors[error as keyof typeof c.errors] ?? t(c.errors.default, { code: error })) : null;

  return (
    <div className={styles.stack}>
      <div>
        <nav className={styles.crumbs} aria-label={c.breadcrumb}>
          <Link href={`/${lang}/clients`}>{c.title}</Link>
          <span aria-hidden="true">/</span>
          <Link href={`/${lang}/clients/${companyId}`}>{company.data.legalName}</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{flow.data.name}</span>
        </nav>
        <div className={styles.pageHead} style={{ marginBottom: 0 }}>
          <h1>
            {flow.data.name} <FlowStatusBadge status={flow.data.status} dict={dict} />
          </h1>
        </div>
      </div>

      {saved && (
        <p className={styles.notice} role="status">
          {c.flow.saved}
        </p>
      )}
      {errorText && (
        <p className={styles.message} role="alert">
          {errorText}
        </p>
      )}

      <section className={styles.panel}>
        <div className={styles.how}>
          <FlowRoute flow={flow.data} dict={dict} live={flow.data.status === "ACTIVE"} />
        </div>
      </section>

      <div className={styles.gridTwo}>
        <section className={styles.panel} aria-labelledby="messages-title">
          <div className={styles.panelHead}>
            <h2 id="messages-title">{c.flow.messages}</h2>
          </div>
          {invoices.data.length === 0 ? (
            <p className={styles.empty}>{c.flow.noMessages}</p>
          ) : (
            <ul className={styles.feed}>
              {invoices.data.map((i) => (
                <li key={i.id}>
                  <time className={styles.time} dateTime={i.createdAt}>
                    {formatDateTime(lang, i.createdAt)}
                  </time>
                  <StatusBadge status={i.status} label={dict.status[i.status]} />
                  <span className={styles.feedText}>
                    <Link href={`/${lang}/invoices/${i.id}`} className={styles.mono}>
                      {i.number}
                    </Link>{" "}
                    · {i.buyerName ?? dict.common.none} · <bdi>{formatAmount(lang, i.payableAmount, i.currency)}</bdi>{" "}
                    {i.clearance.simulated && <SimulatedBadge label={dict.common.simulatedBadge} />}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className={styles.panel} aria-labelledby="config-title">
          <div className={styles.panelHead}>
            <h2 id="config-title">{c.flow.config}</h2>
          </div>
          <form action={updateFlowAction} className={styles.form}>
            <input type="hidden" name="lang" value={lang} />
            <input type="hidden" name="companyId" value={companyId} />
            <input type="hidden" name="flowId" value={flowId} />
            <label className={styles.field}>
              <span>{c.flow.name}</span>
              <input name="name" defaultValue={flow.data.name} maxLength={120} required />
            </label>
            <label className={styles.field}>
              <span>{c.flow.statusLabel}</span>
              <select name="status" defaultValue={flow.data.status}>
                {(["ACTIVE", "PAUSED", "DRAFT"] as const).map((s) => (
                  <option key={s} value={s}>
                    {c.status[s]}
                  </option>
                ))}
              </select>
            </label>
            <p className={styles.formNote}>{c.flow.pauseNote}</p>
            <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`}>
              {c.flow.save}
            </button>
            <code className={styles.code}>{t(c.flow.apiHint, { id: flowId })}</code>
            <p className={styles.formNote}>{c.flow.defaultHint}</p>
          </form>
        </section>
      </div>

      <div>
        <div className={styles.pageHead} style={{ marginBottom: 8 }}>
          <h2 style={{ margin: 0, fontSize: 17 }}>{c.flow.mappingTitle}</h2>
          <span className={styles.muted} style={{ fontSize: 12.5 }}>
            {latest ? t(c.flow.mappingFrom, { number: latest.number }) : c.flow.mappingEmpty}
          </span>
          <span className={styles.muted} style={{ fontSize: 12.5 }}>
            {c.flow.editSoon}
          </span>
        </div>
        {rows.length > 0 && <MappingBoard rows={rows} dict={{ mapping: dict.mapping }} structureOnly={!latest} />}
      </div>
    </div>
  );
}
