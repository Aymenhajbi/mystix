import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { DirectionBadge, FlowRoute, FlowStatusBadge } from "@/components/FlowChips";
import {
  getCompany,
  isUuid,
  listEnvironments,
  listFlows,
  listPartners,
  portalCompanyId,
  PARTNER_TYPES,
  type ExchangeFlow,
  type FlowDirection,
  type Partner,
} from "@/lib/api";
import { formatDateTime, hasLocale, intlTag, type Locale } from "@/lib/i18n";
import { getDictionary, t, type Dictionary } from "../../dictionaries";
import styles from "../../portal.module.css";
import { createPartnerAction } from "../actions";

export async function generateMetadata({ params }: PageProps<"/[lang]/clients/[companyId]">): Promise<Metadata> {
  const { lang, companyId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId)) return {};
  const company = await getCompany(companyId);
  return { title: company.kind === "ok" ? company.data.legalName : (await getDictionary(lang)).clients.title };
}

function FlowSection({
  direction,
  flows,
  partners,
  lang,
  companyId,
  dict,
}: {
  direction: FlowDirection;
  flows: ExchangeFlow[];
  partners: Map<string, Partner>;
  lang: Locale;
  companyId: string;
  dict: Dictionary;
}) {
  const c = dict.clients;
  const id = `flows-${direction.toLowerCase()}`;
  return (
    <section className={styles.panel} aria-labelledby={id}>
      <div className={styles.panelHead}>
        <h2 id={id}>
          <DirectionBadge direction={direction} dict={dict} /> {c.directionTitle[direction]}
        </h2>
        <span className={styles.hint}>{c.directionHint[direction]}</span>
        <div className={styles.right}>
          <Link href={`/${lang}/clients/${companyId}/flows/new?direction=${direction}`} className={styles.btn}>
            {c.newFlowFor[direction]}
          </Link>
        </div>
      </div>
      {flows.length === 0 ? (
        <p className={styles.empty}>{c.noFlowsFor[direction]}</p>
      ) : (
        <ul className={styles.flowList}>
          {flows.map((f) => {
            const partner = f.partnerId ? partners.get(f.partnerId) : undefined;
            return (
              <li key={f.id}>
                <Link href={`/${lang}/clients/${companyId}/flows/${f.id}`} className={styles.flowCard}>
                  <span className={styles.flowCardHead}>
                    <b>{f.name}</b>
                    <span className={styles.flowCardTags}>
                      {!f.executable && <span className={`${styles.sev} ${styles["sev-info"]}`}>{c.declared}</span>}
                      <FlowStatusBadge status={f.status} dict={dict} />
                    </span>
                  </span>
                  <span className={styles.flowCardMeta}>
                    {c.partner.label} : {partner ? `${partner.name} · ${c.partner.types[partner.type]}` : c.partner.all}
                  </span>
                  <FlowRoute flow={f} dict={dict} live={f.status === "ACTIVE"} />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

export default async function ClientEnvironmentPage({ params, searchParams }: PageProps<"/[lang]/clients/[companyId]">) {
  const { lang, companyId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.clients;
  const query = await searchParams;
  const error = Array.isArray(query.error) ? query.error[0] : query.error;
  const partnerSaved = query.partner === "1";
  const [company, flows, partners, environments, active] = await Promise.all([
    getCompany(companyId),
    listFlows(companyId),
    listPartners(companyId),
    listEnvironments(),
    portalCompanyId(),
  ]);
  if (company.kind === "not-found") notFound();
  if (company.kind !== "ok" || flows.kind !== "ok" || partners.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const env = environments.kind === "ok" ? environments.data.find((e) => e.id === companyId) : undefined;
  const integer = new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" });
  const activeCount = flows.data.filter((f) => f.status === "ACTIVE").length;
  const outbound = flows.data.filter((f) => f.direction === "OUT");
  const inbound = flows.data.filter((f) => f.direction === "IN");
  const partnerById = new Map(partners.data.map((p) => [p.id, p]));
  const errorText = error ? (c.errors[error as keyof typeof c.errors] ?? t(c.errors.default, { code: error })) : null;

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
      {errorText && (
        <p className={styles.message} role="alert">
          {errorText}
        </p>
      )}
      {partnerSaved && (
        <p className={styles.notice} role="status">
          {c.partner.saved}
        </p>
      )}

      <section className={styles.strip} style={{ gridTemplateColumns: "repeat(4, minmax(0, 1fr))" }}>
        <div>
          <span className={styles.lbl}>{c.stripFlows}</span>
          <span className={styles.big}>{integer.format(flows.data.length)}</span>
          <span className={styles.sub}>
            {t(c.stripDirections, { out: outbound.length, in: inbound.length })} · {t(c.stripActive, { count: activeCount })}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{c.partner.strip}</span>
          <span className={styles.big}>{integer.format(partners.data.length)}</span>
          <span className={styles.sub}>
            {t(c.partner.stripTypes, { count: new Set(partners.data.map((p) => p.type)).size })}
          </span>
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

      <FlowSection direction="OUT" flows={outbound} partners={partnerById} lang={lang} companyId={companyId} dict={dict} />
      <FlowSection direction="IN" flows={inbound} partners={partnerById} lang={lang} companyId={companyId} dict={dict} />

      <section className={styles.panel} aria-labelledby="partners-title" id="partners">
        <div className={styles.panelHead}>
          <h2 id="partners-title">{c.partner.title}</h2>
          <span className={styles.hint}>{c.partner.hint}</span>
        </div>
        {partners.data.length === 0 ? (
          <p className={styles.empty}>{c.partner.empty}</p>
        ) : (
          <ul className={styles.partnerList}>
            {partners.data.map((p) => {
              const count = flows.data.filter((f) => f.partnerId === p.id).length;
              return (
                <li key={p.id}>
                  <span className={`${styles.chip} ${styles.chipNeutral}`}>{c.partner.types[p.type]}</span>
                  <b>{p.name}</b>
                  <span className={styles.muted} style={{ fontSize: 12.5 }}>
                    {t(c.partner.flowCount, { count })}
                  </span>
                  <span className={styles.partnerIds}>
                    {[p.ice && `ICE ${p.ice}`, p.gln && `GLN ${p.gln}`, p.reference && `${c.partner.ref} ${p.reference}`]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
        <form action={createPartnerAction} className={styles.form}>
          <input type="hidden" name="lang" value={lang} />
          <input type="hidden" name="companyId" value={companyId} />
          <h3 style={{ margin: 0, fontSize: 14 }}>{c.partner.add}</h3>
          <div className={styles.formRow}>
            <label className={styles.field}>
              <span>{c.partner.name}</span>
              <input name="name" required maxLength={160} />
            </label>
            <label className={styles.field}>
              <span>{c.partner.type}</span>
              <select name="type" defaultValue="CUSTOMER" required>
                {PARTNER_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {c.partner.types[type]}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className={styles.formRow}>
            <label className={styles.field}>
              <span>{c.partner.ice}</span>
              <input name="ice" inputMode="numeric" pattern="[0-9]{15}" maxLength={15} dir="ltr" />
            </label>
            <label className={styles.field}>
              <span>{c.partner.gln}</span>
              <input name="gln" inputMode="numeric" pattern="[0-9]{13}" maxLength={13} dir="ltr" />
            </label>
            <label className={styles.field}>
              <span>{c.partner.reference}</span>
              <input name="reference" maxLength={60} />
            </label>
          </div>
          <div className={styles.formActions}>
            <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`}>
              {c.partner.submit}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
