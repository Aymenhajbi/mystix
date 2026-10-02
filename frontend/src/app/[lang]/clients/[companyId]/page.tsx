import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { FlowRoute, FlowStatusBadge } from "@/components/FlowChips";
import { getCompany, isUuid, listEnvironments, listFlows, portalCompanyId } from "@/lib/api";
import { formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { getDictionary, t } from "../../dictionaries";
import styles from "../../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/clients/[companyId]">): Promise<Metadata> {
  const { lang, companyId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId)) return {};
  const company = await getCompany(companyId);
  return { title: company.kind === "ok" ? company.data.legalName : (await getDictionary(lang)).clients.title };
}

export default async function ClientEnvironmentPage({ params }: PageProps<"/[lang]/clients/[companyId]">) {
  const { lang, companyId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.clients;
  const [company, flows, environments, active] = await Promise.all([
    getCompany(companyId),
    listFlows(companyId),
    listEnvironments(),
    portalCompanyId(),
  ]);
  if (company.kind === "not-found") notFound();
  if (company.kind !== "ok" || flows.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const env = environments.kind === "ok" ? environments.data.find((e) => e.id === companyId) : undefined;
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });
  const activeCount = flows.data.filter((f) => f.status === "ACTIVE").length;

  return (
    <div className={styles.stack}>
      <div>
        <nav className={styles.crumbs} aria-label={c.breadcrumb}>
          <Link href={`/${lang}/clients`}>{c.title}</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{company.data.legalName}</span>
        </nav>
        <div className={styles.pageHead} style={{ marginBottom: 0 }}>
          <span className={styles.envMono} aria-hidden="true">
            {company.data.legalName.slice(0, 1).toUpperCase()}
          </span>
          <div>
            <h1>{company.data.legalName}</h1>
            <span className={`${styles.mono} ${styles.muted}`}>ICE {company.data.ice}</span>
          </div>
          <div className={styles.right}>
            <Link href={`/${lang}/clients/${companyId}/flows/new`} className={`${styles.btn} ${styles.btnPrimary}`}>
              {c.newFlow}
            </Link>
          </div>
        </div>
      </div>

      {active !== companyId && (
        <p className={styles.message}>
          {c.notActive}{" "}
          <a href={`/api/context?company=${companyId}&next=${encodeURIComponent(`/${lang}/clients/${companyId}`)}`}>
            {c.activate}
          </a>
        </p>
      )}

      <section className={styles.strip} style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
        <div>
          <span className={styles.lbl}>{c.stripFlows}</span>
          <span className={styles.big}>{integer.format(flows.data.length)}</span>
          <span className={styles.sub}>{t(c.stripActive, { count: activeCount })}</span>
        </div>
        <div>
          <span className={styles.lbl}>{c.stripInvoices}</span>
          <span className={styles.big}>{env ? integer.format(env.invoices) : "—"}</span>
          <span className={styles.sub}>
            {c.colLast} : {env?.lastActivity ? formatDateTime(lang, env.lastActivity) : c.never}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{c.stripErrors}</span>
          <span className={`${styles.big} ${env && env.errors24h > 0 ? styles.tCrit : ""}`}>
            {env ? integer.format(env.errors24h) : "—"}
          </span>
          <span className={styles.sub}>
            <Link href={`/${lang}/logs?level=ERROR`}>{dict.logs.seeAll}</Link>
          </span>
        </div>
      </section>

      <section className={styles.panel} aria-labelledby="flows-title">
        <div className={styles.panelHead}>
          <h2 id="flows-title">{c.flows}</h2>
          <span className={styles.hint}>{c.flowsHint}</span>
        </div>
        {flows.data.length === 0 ? (
          <p className={styles.empty}>{c.noFlows}</p>
        ) : (
          <ul className={styles.flowList}>
            {flows.data.map((f) => (
              <li key={f.id}>
                <Link href={`/${lang}/clients/${companyId}/flows/${f.id}`} className={styles.flowCard}>
                  <span className={styles.flowCardHead}>
                    <b>{f.name}</b>
                    <FlowStatusBadge status={f.status} dict={dict} />
                  </span>
                  <FlowRoute flow={f} dict={dict} live={f.status === "ACTIVE"} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
