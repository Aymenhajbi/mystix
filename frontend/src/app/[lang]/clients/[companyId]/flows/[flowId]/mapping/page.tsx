import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { FlowStatusBadge } from "@/components/FlowChips";
import { MappingEditor } from "@/components/MappingEditor";
import { getCompany, getFlow, getFlowMapping, isUuid } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../../../dictionaries";
import styles from "../../../../../portal.module.css";

type Params = PageProps<"/[lang]/clients/[companyId]/flows/[flowId]/mapping">;

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).studio.title } : {};
}

export default async function MappingStudioPage({ params, searchParams }: Params) {
  const { lang, companyId, flowId } = await params;
  if (!hasLocale(lang) || !isUuid(companyId) || !isUuid(flowId)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const query = await searchParams;
  const requested = Number(Array.isArray(query.v) ? query.v[0] : query.v);

  const [company, flow, mapping] = await Promise.all([
    getCompany(companyId),
    getFlow(companyId, flowId),
    getFlowMapping(companyId, flowId),
  ]);
  if (company.kind === "not-found" || flow.kind === "not-found") notFound();
  if (company.kind !== "ok" || flow.kind !== "ok" || mapping.kind !== "ok") {
    return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  }
  const initial = mapping.data.versions.some((v) => v.version === requested) ? requested : null;

  return (
    <div className={styles.stack}>
      <div>
        <nav className={styles.crumbs} aria-label={dict.clients.breadcrumb}>
          <Link href={`/${lang}/clients`}>{dict.clients.title}</Link>
          <span aria-hidden="true">/</span>
          <Link href={`/${lang}/clients/${companyId}`}>{company.data.legalName}</Link>
          <span aria-hidden="true">/</span>
          <Link href={`/${lang}/clients/${companyId}/flows/${flowId}`}>{flow.data.name}</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{dict.studio.title}</span>
        </nav>
        <div className={styles.pageHead} style={{ marginBottom: 0 }}>
          <div>
            <h1>
              {dict.studio.title} <FlowStatusBadge status={flow.data.status} dict={dict} />
            </h1>
            <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "85ch" }}>
              {dict.studio.lead}
            </p>
          </div>
        </div>
      </div>
      <MappingEditor
        key={`${flowId}-${mapping.data.versions.length}`}
        companyId={companyId}
        flowId={flowId}
        mapping={mapping.data}
        initialVersion={initial}
        studio={dict.studio}
        lang={lang}
      />
    </div>
  );
}
