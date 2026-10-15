import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import {
  JOB_WINDOW,
  getQueueStats,
  listFlows,
  listJobs,
  portalCompanyId,
  type JobStatus,
  type QueueJob,
} from "@/lib/api";
import { formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { replayJob } from "@/lib/jobActions";
import { getDictionary, t, type Dictionary } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/jobs">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).jobs.title } : {};
}

type StatusFilter = JobStatus | "all";
const statusFilters: StatusFilter[] = ["DEAD", "READY", "RUNNING", "DONE", "all"];

const sevOf: Record<JobStatus, string> = {
  DEAD: styles["sev-err"],
  READY: styles["sev-warn"],
  RUNNING: styles["sev-info"],
  DONE: styles["sev-ok"],
};

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? "";

/** Jobs of the work queue (ADR-0003): failures first, with their cause and a replay for the definitive ones. */
export default async function JobsPage({ params, searchParams }: PageProps<"/[lang]/jobs">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const j = dict.jobs;
  const query = await searchParams;
  const rawStatus = one(query.status);
  // Failures first: the page opens on the jobs that need someone.
  const status: StatusFilter = statusFilters.includes(rawStatus as StatusFilter) ? (rawStatus as StatusFilter) : "DEAD";
  const replayed = one(query.replayed);
  const error = one(query.error);

  const company = await portalCompanyId();
  const [stats, jobs, flows] = await Promise.all([
    getQueueStats(),
    listJobs(status === "all" ? undefined : status),
    company ? listFlows(company) : Promise.resolve(null),
  ]);
  const flowNames = new Map(flows?.kind === "ok" ? flows.data.map((f) => [`flow:${f.id}`, f.name]) : []);
  const integer = new Intl.NumberFormat(intlTag(lang));

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1>{j.title}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "70ch" }}>
            {j.lead}
          </p>
        </div>
      </div>

      {stats.kind === "no-company" && <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>}
      {(stats.kind === "unreachable" || stats.kind === "not-found") && (
        <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>
      )}
      {replayed && (
        <p className={styles.notice} role="status">
          {j.replayed}
        </p>
      )}
      {error && (
        <p className={styles.message} role="alert">
          {j.errors[error as keyof typeof j.errors] ?? j.errors.default}
        </p>
      )}

      {stats.kind === "ok" && (
        <section className={styles.strip} aria-label={j.stripLabel}>
          <div>
            <span className={styles.lbl}>{j.kpi.dead}</span>
            <span className={`${styles.big} ${stats.data.dead > 0 ? styles.tCrit : ""}`}>
              {integer.format(stats.data.dead)}
            </span>
            <span className={styles.sub}>{j.kpi.deadSub}</span>
          </div>
          <div>
            <span className={styles.lbl}>{j.kpi.ready}</span>
            <span className={styles.big}>{integer.format(stats.data.ready)}</span>
            <span className={styles.sub}>
              {stats.data.oldestReadySeconds === null
                ? j.kpi.readyEmpty
                : t(j.kpi.readySub, { age: age(stats.data.oldestReadySeconds, j) })}
            </span>
          </div>
          <div>
            <span className={styles.lbl}>{j.kpi.running}</span>
            <span className={styles.big}>{integer.format(stats.data.running)}</span>
            <span className={styles.sub}>{j.kpi.runningSub}</span>
          </div>
          <div>
            <span className={styles.lbl}>{j.kpi.done}</span>
            <span className={styles.big}>{integer.format(stats.data.done)}</span>
            <span className={styles.sub}>{j.kpi.doneSub}</span>
          </div>
        </section>
      )}

      {jobs.kind === "ok" && (
        <section className={styles.panel} aria-label={j.title}>
          <div className={styles.bar}>
            <nav className={styles.filters} aria-label={j.filterLabel}>
              {statusFilters.map((filter) => (
                <Link
                  key={filter}
                  href={`/${lang}/jobs${filter === "DEAD" ? "" : `?status=${filter}`}`}
                  className={styles.filter}
                  aria-current={status === filter ? "true" : undefined}
                >
                  {j.filters[filter]}
                </Link>
              ))}
            </nav>
          </div>
          <JobTable lang={lang} dict={dict} jobs={jobs.data} status={status} flowNames={flowNames} />
        </section>
      )}
    </>
  );
}

function age(seconds: number, j: Dictionary["jobs"]) {
  if (seconds < 60) return t(j.age.seconds, { n: seconds });
  if (seconds < 3600) return t(j.age.minutes, { n: Math.floor(seconds / 60) });
  if (seconds < 86400) return t(j.age.hours, { n: Math.floor(seconds / 3600) });
  return t(j.age.days, { n: Math.floor(seconds / 86400) });
}

function JobTable({
  lang,
  dict,
  jobs,
  status,
  flowNames,
}: {
  lang: "fr" | "ar";
  dict: Dictionary;
  jobs: QueueJob[];
  status: StatusFilter;
  flowNames: Map<string, string>;
}) {
  const j = dict.jobs;
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <caption>
          <span className={styles.captionRow}>
            <span>{j.caption}</span>
            <span>
              {t(j.count, { count: jobs.length })}
              {jobs.length >= JOB_WINDOW ? ` · ${t(j.window, { count: JOB_WINDOW })}` : ""}
            </span>
          </span>
        </caption>
        <thead>
          <tr>
            <th scope="col">{j.col.subject}</th>
            <th scope="col">{j.col.flow}</th>
            <th scope="col">{j.col.status}</th>
            <th scope="col" className={styles.num}>
              {j.col.attempts}
            </th>
            <th scope="col">{j.col.error}</th>
            <th scope="col">{j.col.updated}</th>
            <th scope="col">
              <span className={styles.srOnly}>{j.col.actions}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {jobs.length === 0 && (
            <tr>
              <td colSpan={7} className={styles.empty}>
                {status === "DEAD" ? j.noneDead : j.none}
              </td>
            </tr>
          )}
          {jobs.map((job) => {
            const number = typeof job.payload?.number === "string" ? job.payload.number : null;
            const kind = j.kinds[job.kind as keyof typeof j.kinds] ?? job.kind;
            const flow = flowNames.get(job.lane) ?? (job.lane.startsWith("company:") ? j.noFlow : job.lane);
            const err = job.lastError;
            return (
              <tr key={job.id}>
                <th scope="row" style={{ fontWeight: 400 }}>
                  <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>
                    {kind}
                  </span>
                  {job.kind === "INVOICE_CLEARANCE" ? (
                    <Link href={`/${lang}/invoices/${job.subjectId}`} className={styles.strongLink}>
                      <bdi className={styles.mono}>{number ?? job.subjectId}</bdi>
                    </Link>
                  ) : (
                    <bdi className={styles.mono}>{number ?? job.subjectId}</bdi>
                  )}
                </th>
                <td>{flow}</td>
                <td>
                  <span className={`${styles.sev} ${sevOf[job.status]}`}>{j.statuses[job.status]}</span>
                </td>
                <td className={styles.num}>
                  <bdi>
                    {job.attempts} / {job.maxAttempts}
                  </bdi>
                </td>
                <td className={styles.wrapCell}>
                  {err ? (
                    <>
                      <span className={styles.mono}>{err.errorCode ?? j.unknownError}</span>
                      {err.retryable === false && <span className={styles.muted}> · {j.permanent}</span>}
                      {err.message && (
                        <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>
                          <bdi>{err.message}</bdi>
                        </span>
                      )}
                    </>
                  ) : (
                    <span className={styles.muted}>{dict.common.none}</span>
                  )}
                </td>
                <td>
                  {formatDateTime(lang, job.updatedAt)}
                  {job.status === "READY" && job.attempts > 0 && (
                    <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>
                      {t(j.nextAttempt, { at: formatDateTime(lang, job.availableAt) })}
                    </span>
                  )}
                </td>
                <td>
                  {job.status === "DEAD" && (
                    <form action={replayJob}>
                      <input type="hidden" name="lang" value={lang} />
                      <input type="hidden" name="id" value={job.id} />
                      {status !== "DEAD" && <input type="hidden" name="status" value={status} />}
                      <button
                        type="submit"
                        className={`${styles.btn} ${styles.btnSm}`}
                        aria-label={t(j.replayLabel, { subject: number ?? job.subjectId })}
                      >
                        {j.replay}
                      </button>
                    </form>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
