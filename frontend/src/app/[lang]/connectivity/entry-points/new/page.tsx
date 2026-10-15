import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import channelStyles from "@/components/channels/channels.module.css";
import { listFlows, portalCompanyId } from "@/lib/api";
import { createEntryPointAction } from "@/lib/connectivityActions";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";
import styles from "../../../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/connectivity/entry-points/new">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).connectivity.form.entryTitle } : {};
}

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? "";

export default async function NewEntryPointPage({ params, searchParams }: PageProps<"/[lang]/connectivity/entry-points/new">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.connectivity;
  const f = c.form;
  const query = await searchParams;
  const error = one(query.error);
  const invalid = new Set(one(query.fields).split(",").filter(Boolean));
  const company = await portalCompanyId();
  const flows = company ? await listFlows(company) : null;
  const flowList = flows?.kind === "ok" ? flows.data : [];

  return (
    <>
      <nav className={styles.crumbs} aria-label="breadcrumb">
        <Link href={`/${lang}/connectivity`}>{c.title}</Link> / <span>{c.newEntryPoint}</span>
      </nav>
      <div className={styles.pageHead}>
        <div>
          <h1>{f.entryTitle}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "75ch" }}>
            {f.entryLead}
          </p>
        </div>
      </div>
      <form action={createEntryPointAction} className={styles.panel} style={{ padding: 20 }}>
        <input type="hidden" name="lang" value={lang} />
        {error && (
          <p className={channelStyles.alert} role="alert">
            {c.errors[error as keyof typeof c.errors] ?? c.errors.default}
          </p>
        )}
        <div className={channelStyles.fields}>
          <label className={channelStyles.field} data-invalid={invalid.has("code") || undefined}>
            <span>{f.code}<span className={channelStyles.req} aria-hidden="true"> *</span></span>
            <input name="code" required maxLength={60} pattern="[A-Za-z0-9][A-Za-z0-9_\-]{0,59}" autoComplete="off" dir="ltr" />
            <span className={channelStyles.hint}>{f.codeHint}</span>
          </label>
          <label className={channelStyles.field} data-invalid={invalid.has("name") || undefined}>
            <span>{f.name}<span className={channelStyles.req} aria-hidden="true"> *</span></span>
            <input name="name" required maxLength={120} autoComplete="off" />
          </label>
          <label className={channelStyles.field} data-invalid={invalid.has("flowId") || undefined}>
            <span>{f.flow}<span className={channelStyles.req} aria-hidden="true"> *</span></span>
            <select name="flowId" required defaultValue="">
              <option value="" disabled>—</option>
              {flowList.map((flow) => (
                <option key={flow.id} value={flow.id}>
                  {flow.name} · {flow.status}
                </option>
              ))}
            </select>
          </label>
          <label className={channelStyles.field}>
            <span>{f.format}</span>
            <select name="inputFormat" defaultValue="JSON">
              {["JSON", "XML", "EDIFACT", "CSV"].map((format) => (
                <option key={format} value={format}>{format}</option>
              ))}
            </select>
            <span className={channelStyles.hint}>{f.formatHint}</span>
          </label>
        </div>
        <div className={channelStyles.footer}>
          <Link href={`/${lang}/connectivity`} className={styles.btn}>{f.cancel}</Link>
          <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`}>{f.createEntry}</button>
        </div>
      </form>
    </>
  );
}
