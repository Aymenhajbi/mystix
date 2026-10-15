import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { ConfirmButton } from "@/components/channels/ConfirmButton";
import channelStyles from "@/components/channels/channels.module.css";
import { KeyRotator } from "@/components/connectivity/KeyRotator";
import { LifecycleNotice } from "@/components/connectivity/LifecycleNotice";
import { isUuid, listFlows, portalCompanyId } from "@/lib/api";
import { channelsApi } from "@/lib/channels";
import { connectivityApi, receptionUrl, stateTone } from "@/lib/connectivity";
import { listenerLifecycleAction } from "@/lib/connectivityActions";
import { formatDateTime, hasLocale, intlTag } from "@/lib/i18n";
import { getDictionary, t } from "../../../dictionaries";
import styles from "../../../portal.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/connectivity/listeners/[id]">): Promise<Metadata> {
  const { lang, id } = await params;
  if (!hasLocale(lang) || !isUuid(id)) return {};
  const listener = await connectivityApi.listener(id);
  return { title: listener.kind === "ok" ? listener.data.name : (await getDictionary(lang)).connectivity.title };
}

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? "";
const sevClass = { INFO: "sev-info", WARN: "sev-warn", ERROR: "sev-err" } as const;

/** One listener: its endpoint, desired and observed state, diagnostic, key, and its journal. */
export default async function ListenerPage({ params, searchParams }: PageProps<"/[lang]/connectivity/listeners/[id]">) {
  const { lang, id } = await params;
  if (!hasLocale(lang) || !isUuid(id)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const c = dict.connectivity;
  const d = c.detail;
  const query = await searchParams;
  const [listener, events] = await Promise.all([connectivityApi.listener(id), connectivityApi.events(id)]);
  if (listener.kind === "not-found") notFound();
  if (listener.kind !== "ok") return <p className={styles.message} role="alert">{dict.common.apiUnreachable}</p>;
  const l = listener.data;
  const company = await portalCompanyId();
  const [channel, entryPoints, flows] = await Promise.all([
    channelsApi.get(l.channelId),
    connectivityApi.entryPoints(),
    company ? listFlows(company) : Promise.resolve(null),
  ]);
  const entry = entryPoints.kind === "ok" ? entryPoints.data.find((e) => e.id === l.entryPointId) : undefined;
  const flow = flows?.kind === "ok" && entry ? flows.data.find((f) => f.id === entry.flowId) : undefined;
  const url = receptionUrl(l.endpoint);
  const integer = new Intl.NumberFormat(intlTag(lang));
  const when = (v: string | null) => (v ? formatDateTime(lang, v) : c.never);

  return (
    <>
      <nav className={styles.crumbs} aria-label="breadcrumb">
        <Link href={`/${lang}/connectivity`}>{c.title}</Link> / <span>{l.name}</span>
      </nav>
      <div className={styles.pageHead}>
        <div>
          <h1>{l.name}</h1>
          <p className={styles.muted} style={{ margin: "6px 0 0" }}>
            {c.kinds[l.kind]} ·{" "}
            <span className={`${styles.sev} ${styles[`sev-${stateTone(l.state)}`]}`}>{c.states[l.state]}</span>{" "}
            · {c.col.desired} : {c.desired[l.desiredState]}
          </p>
        </div>
        <form action={listenerLifecycleAction} className={styles.actions}>
          <input type="hidden" name="lang" value={lang} />
          <input type="hidden" name="id" value={l.id} />
          {l.desiredState === "RUNNING" ? (
            <>
              <input type="hidden" name="action" value="stop" />
              <ConfirmButton message={c.stopConfirm} className={styles.btn}>{c.stop}</ConfirmButton>
            </>
          ) : (
            <>
              <input type="hidden" name="action" value="start" />
              <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`} disabled={!l.enabled}>
                {c.start}
              </button>
            </>
          )}
        </form>
      </div>

      <LifecycleNotice dict={dict} query={{ started: one(query.started), stopped: one(query.stopped), error: one(query.error), why: one(query.why) }} />

      {l.lastError && (l.state === "ERROR" || l.state === "DEGRADED") && (
        <div className={styles.message} role="alert">
          <strong>{d.diagnostic} : </strong>
          <span className={styles.mono}>{l.lastError.code}</span>
          {l.lastError.detail && <span> · {l.lastError.detail}</span>}
        </div>
      )}

      <div className={styles.gridTwo}>
        <section className={styles.panel} aria-labelledby="endpoint-title" style={{ padding: 20 }}>
          <h2 id="endpoint-title" style={{ marginTop: 0 }}>{d.endpoint}</h2>
          <dl className={channelStyles.kv}>
            <div>
              <dt>{d.method}</dt>
              <dd className={styles.mono}>POST</dd>
            </div>
            <div>
              <dt>{d.endpoint}</dt>
              <dd className={styles.mono} dir="ltr" style={{ wordBreak: "break-all" }}>{url ?? dict.common.none}</dd>
            </div>
            <div>
              <dt>{d.key}</dt>
              <dd>
                {l.apiKeyHint ? t(d.keyHint, { hint: l.apiKeyHint }) : dict.common.none}
              </dd>
            </div>
            <div>
              <dt>{c.col.channel}</dt>
              <dd>{channel.kind === "ok" ? `${channel.data.name} · ${channel.data.protocol} · ${channel.data.status}` : dict.common.none}</dd>
            </div>
            <div>
              <dt>{c.col.entryPoint}</dt>
              <dd>{entry ? `${entry.code} · ${entry.inputFormat}` : dict.common.none}{flow ? ` → ${flow.name}` : ""}</dd>
            </div>
          </dl>
          <p className={styles.muted} style={{ fontSize: "0.9em" }}>{d.endpointHint}</p>
          <KeyRotator lang={lang} id={l.id} dict={dict} />
          {url && (
            <>
              <h3 style={{ marginBottom: 4 }}>{d.try}</h3>
              <p className={styles.muted} style={{ margin: "0 0 6px", fontSize: "0.9em" }}>{d.tryLead}</p>
              <pre className={styles.code} dir="ltr" style={{ whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
                {`curl -X POST "${url}" \\\n  -H "X-Mystix-Api-Key: <API_KEY>" \\\n  -H "Content-Type: application/json" \\\n  --data-binary @facture.json`}
              </pre>
            </>
          )}
        </section>

        <section className={styles.panel} aria-labelledby="runtime-title" style={{ padding: 20 }}>
          <h2 id="runtime-title" style={{ marginTop: 0 }}>{d.runtime}</h2>
          <dl className={channelStyles.kv}>
            <div><dt>{c.col.state}</dt><dd>{c.states[l.state]}</dd></div>
            <div><dt>{c.col.desired}</dt><dd>{c.desired[l.desiredState]}</dd></div>
            <div><dt>{d.heartbeat}</dt><dd>{when(l.lastHeartbeatAt)}</dd></div>
            <div><dt>{d.startedAt}</dt><dd>{when(l.startedAt)}</dd></div>
            <div><dt>{d.stoppedAt}</dt><dd>{when(l.stoppedAt)}</dd></div>
            <div><dt>{c.col.activity}</dt><dd>{when(l.lastActivityAt)}</dd></div>
            <div><dt>{c.col.counts}</dt><dd>{integer.format(l.receivedCount)} / {integer.format(l.rejectedCount)}</dd></div>
            <div><dt>{d.instance}</dt><dd className={styles.mono} style={{ wordBreak: "break-all" }}>{l.runtimeInstance ?? dict.common.none}</dd></div>
          </dl>
        </section>
      </div>

      <section className={styles.panel} aria-labelledby="events-title" style={{ marginTop: 24 }}>
        <div className={styles.panelHead}>
          <h2 id="events-title">{d.events}</h2>
        </div>
        {events.kind !== "ok" || events.data.length === 0 ? (
          <p className={styles.empty}>{d.noEvents}</p>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">{d.eventCol.at}</th>
                  <th scope="col">{d.eventCol.type}</th>
                  <th scope="col">{d.eventCol.details}</th>
                  <th scope="col">{d.eventCol.correlation}</th>
                  <th scope="col">{d.eventCol.actor}</th>
                </tr>
              </thead>
              <tbody>
                {events.data.map((e) => (
                  <tr key={e.id}>
                    <td>{formatDateTime(lang, e.createdAt)}</td>
                    <td>
                      <span className={`${styles.sev} ${styles[sevClass[e.severity]]}`}>
                        {c.eventTypes[e.eventType as keyof typeof c.eventTypes] ?? e.eventType}
                      </span>
                    </td>
                    <td className={styles.wrapCell}>
                      <EventDetails lang={lang} details={e.details} />
                    </td>
                    <td><bdi className={styles.mono}>{e.correlationId ? e.correlationId.slice(0, 8) : dict.common.none}</bdi></td>
                    <td>{e.actor ?? dict.common.none}</td>
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

/** Details of an event; an accepted invoice links to its sheet. Values are sanitized by the backend. */
function EventDetails({ lang, details }: { lang: string; details: Record<string, unknown> }) {
  const invoiceId = typeof details.invoiceId === "string" ? details.invoiceId : null;
  const rest = Object.entries(details).filter(([k]) => k !== "invoiceId" && k !== "sha256");
  return (
    <>
      {invoiceId && (
        <Link href={`/${lang}/invoices/${invoiceId}`} className={styles.strongLink}>
          <bdi className={styles.mono}>{String(details.number ?? invoiceId)}</bdi>
        </Link>
      )}{" "}
      <span className={styles.muted} style={{ fontSize: "0.85em" }}>
        {rest.filter(([k]) => k !== "number").map(([k, v]) => `${k}: ${String(v)}`).join(" · ")}
      </span>
    </>
  );
}
