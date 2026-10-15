import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { ConfirmButton } from "@/components/channels/ConfirmButton";
import { LifecycleNotice } from "@/components/connectivity/LifecycleNotice";
import { listFlows, portalCompanyId } from "@/lib/api";
import { channelsApi } from "@/lib/channels";
import { connectivityApi, stateTone, type ListenerView } from "@/lib/connectivity";
import { listenerLifecycleAction } from "@/lib/connectivityActions";
import { formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { getDictionary, t } from "../dictionaries";
import styles from "../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/connectivity">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).connectivity.title } : {};
}

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? "";

/** Connectivity Center (ADR-0016): channels, listeners with their observed state, and entry points. */
export default async function ConnectivityPage({ params, searchParams }: PageProps<"/[lang]/connectivity">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.connectivity;
  const query = await searchParams;
  const company = await portalCompanyId();
  const [listeners, entryPoints, channels, flows] = await Promise.all([
    connectivityApi.listeners(),
    connectivityApi.entryPoints(),
    channelsApi.list(),
    company ? listFlows(company) : Promise.resolve(null),
  ]);
  const integer = new Intl.NumberFormat(intlTag(lang));

  if (listeners.kind === "no-company") return <p className={styles.message} role="alert">{dict.common.devCompanyMissing}</p>;
  if (listeners.kind !== "ok") return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;

  const channelList = channels.kind === "ok" ? channels.data : [];
  const channelById = new Map(channelList.map((ch) => [ch.id, ch]));
  const entries = entryPoints.kind === "ok" ? entryPoints.data : [];
  const entryById = new Map(entries.map((e) => [e.id, e]));
  const flowName = new Map(flows?.kind === "ok" ? flows.data.map((f) => [f.id, f.name]) : []);
  const all = listeners.data;
  const running = all.filter((l) => l.state === "RUNNING").length;
  const trouble = all.filter((l) => l.state === "ERROR" || l.state === "DEGRADED").length;
  const received = all.reduce((s, l) => s + l.receivedCount, 0);
  const rejected = all.reduce((s, l) => s + l.rejectedCount, 0);

  return (
    <>
      <div className={styles.pageHead}>
        <div>
          <h1>{c.title}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0", maxWidth: "75ch" }}>
            {c.lead}
          </p>
        </div>
        <div className={styles.actions}>
          <Link href={`/${lang}/connectivity/entry-points/new`} className={styles.btn}>
            {c.newEntryPoint}
          </Link>
          <Link href={`/${lang}/connectivity/listeners/new`} className={`${styles.btn} ${styles.btnPrimary}`}>
            {c.newListener}
          </Link>
        </div>
      </div>

      <LifecycleNotice dict={dict} query={{ started: one(query.started), stopped: one(query.stopped),
        entryPoint: one(query.entryPoint), error: one(query.error), why: one(query.why) }} />

      <section className={styles.strip} aria-label={c.stripLabel}>
        <div>
          <span className={styles.lbl}>{c.kpi.channels}</span>
          <span className={styles.big}>{integer.format(channelList.length)}</span>
          <span className={styles.sub}>
            {t(c.kpi.channelsSub, { active: channelList.filter((ch) => ch.status === "ACTIVE").length })}
          </span>
        </div>
        <div>
          <span className={styles.lbl}>{c.kpi.running}</span>
          <span className={`${styles.big} ${running > 0 ? styles.tOk : ""}`}>{integer.format(running)}</span>
          <span className={styles.sub}>{t(c.kpi.runningSub, { total: all.length })}</span>
        </div>
        <div>
          <span className={styles.lbl}>{c.kpi.trouble}</span>
          <span className={`${styles.big} ${trouble > 0 ? styles.tCrit : ""}`}>{integer.format(trouble)}</span>
          <span className={styles.sub}>{c.kpi.troubleSub}</span>
        </div>
        <div>
          <span className={styles.lbl}>{c.kpi.messages}</span>
          <span className={styles.big}>{integer.format(received)}</span>
          <span className={styles.sub}>{t(c.kpi.messagesSub, { rejected })}</span>
        </div>
      </section>

      <section className={styles.panel} aria-labelledby="listeners-title" style={{ marginBottom: 24 }}>
        <div className={styles.panelHead}>
          <h2 id="listeners-title">{c.listeners}</h2>
        </div>
        {all.length === 0 ? (
          <p className={styles.empty}>{c.noListener}</p>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">{c.col.name}</th>
                  <th scope="col">{c.col.channel}</th>
                  <th scope="col">{c.col.entryPoint}</th>
                  <th scope="col">{c.col.state}</th>
                  <th scope="col">{c.col.activity}</th>
                  <th scope="col" className={styles.num}>{c.col.counts}</th>
                  <th scope="col"><span className={styles.srOnly}>{c.col.actions}</span></th>
                </tr>
              </thead>
              <tbody>
                {all.map((l) => {
                  const ch = channelById.get(l.channelId);
                  const ep = entryById.get(l.entryPointId);
                  return (
                    <tr key={l.id}>
                      <th scope="row" style={{ fontWeight: 400 }}>
                        <Link href={`/${lang}/connectivity/listeners/${l.id}`} className={styles.strongLink}>
                          {l.name}
                        </Link>
                        <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>
                          {c.kinds[l.kind]}
                        </span>
                      </th>
                      <td>
                        {ch ? ch.name : dict.common.none}
                        {ch && <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>{ch.protocol} · {ch.direction} · {ch.status}</span>}
                      </td>
                      <td>
                        {ep ? <bdi className={styles.mono}>{ep.code}</bdi> : dict.common.none}
                        {ep && <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>{flowName.get(ep.flowId) ?? ""}</span>}
                      </td>
                      <td>
                        <StateBadge listener={l} label={c.states[l.state]} />
                        <span className={styles.muted} style={{ display: "block", fontSize: "0.85em" }}>
                          {c.col.desired} : {c.desired[l.desiredState]}
                        </span>
                      </td>
                      <td>{l.lastActivityAt ? formatDateTime(lang, l.lastActivityAt) : <span className={styles.muted}>{c.never}</span>}</td>
                      <td className={styles.num}>
                        <bdi>{integer.format(l.receivedCount)} / {integer.format(l.rejectedCount)}</bdi>
                      </td>
                      <td>
                        <form action={listenerLifecycleAction}>
                          <input type="hidden" name="lang" value={lang} />
                          <input type="hidden" name="id" value={l.id} />
                          <input type="hidden" name="back" value="list" />
                          {l.desiredState === "RUNNING" ? (
                            <>
                              <input type="hidden" name="action" value="stop" />
                              <ConfirmButton message={c.stopConfirm} className={`${styles.btn} ${styles.btnSm}`}>{c.stop}</ConfirmButton>
                            </>
                          ) : (
                            <>
                              <input type="hidden" name="action" value="start" />
                              <button type="submit" className={`${styles.btn} ${styles.btnSm} ${styles.btnPrimary}`} disabled={!l.enabled}>
                                {c.start}
                              </button>
                            </>
                          )}
                        </form>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className={styles.panel} aria-labelledby="entry-title">
        <div className={styles.panelHead}>
          <h2 id="entry-title">{c.entryPoints}</h2>
        </div>
        {entries.length === 0 ? (
          <p className={styles.empty}>{c.noEntryPoint}</p>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">{c.col.code}</th>
                  <th scope="col">{c.col.name}</th>
                  <th scope="col">{c.col.flow}</th>
                  <th scope="col">{c.col.format}</th>
                  <th scope="col">{c.col.enabled}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <th scope="row" style={{ fontWeight: 400 }}><bdi className={styles.mono}>{e.code}</bdi></th>
                    <td>{e.name}</td>
                    <td>{flowName.get(e.flowId) ?? dict.common.none}</td>
                    <td>{e.documentType} · {e.inputFormat}</td>
                    <td>{e.enabled ? c.yes : c.no}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function StateBadge({ listener, label }: { listener: ListenerView; label: string }) {
  return <span className={`${styles.sev} ${styles[`sev-${stateTone(listener.state)}`]}`}>{label}</span>;
}
