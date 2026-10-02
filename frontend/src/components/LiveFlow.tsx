"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { LogEntry, LogStat } from "@/lib/api";
import { buildFlow, type StageKey } from "@/lib/flow";
import { formatTime, intlTag, type Locale } from "@/lib/i18n";

export type FlowData = { window: string; since: string; until: string; stats: LogStat[]; recent: LogEntry[] };

const POLL_MS = 5000;

const monogram: Record<StageKey, string> = {
  reception: "API",
  fields: "LECT",
  idempotence: "IDEM",
  ubl: "UBL",
  en16931: "EN",
  storage: "STO",
  clearance: "DGI",
};

/** Where the "rejected" badge of a stage leads in the logs page. */
const rejectedQuery: Record<StageKey, string> = {
  reception: "INTERNAL_ERROR",
  fields: "",
  idempotence: "INVOICE_NUMBER_CONFLICT",
  ubl: "INVOICE_SCHEMA_INVALID",
  en16931: "INVOICE_RULES_VIOLATED",
  storage: "",
  clearance: "CLEARANCE",
};

const levelClass = { INFO: "sev-info", WARN: "sev-warn", ERROR: "sev-err" } as const;

function fill(template: string, values: Record<string, string | number>) {
  return template.replace(/\{(\w+)\}/g, (_, k: string) => String(values[k] ?? ""));
}

/** Live view of the real invoice pipeline, refreshed from the processing log every 5 seconds. */
export function LiveFlow({
  initial,
  lang,
  dict,
}: {
  initial: FlowData;
  lang: Locale;
  dict: Pick<Dictionary, "flow" | "logs">;
}) {
  const f = dict.flow;
  const [data, setData] = useState(initial);
  const [paused, setPaused] = useState(false);
  const [offline, setOffline] = useState(false);
  const [bumped, setBumped] = useState<Set<StageKey>>(new Set());
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const previous = useRef(data);
  const integer = useMemo(() => new Intl.NumberFormat(intlTag(lang), { numberingSystem: "latn" }), [lang]);

  const flow = useMemo(() => buildFlow(data.stats), [data.stats]);

  useEffect(() => {
    if (paused) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const response = await fetch(`/api/flow?window=${encodeURIComponent(initial.window)}`, { cache: "no-store" });
        if (!response.ok) throw new Error(String(response.status));
        const next = (await response.json()) as FlowData;
        if (cancelled) return;
        const before = buildFlow(previous.current.stats);
        const after = buildFlow(next.stats);
        const changed = new Set<StageKey>(
          after.stages.filter((s, i) => s.input !== before.stages[i]?.input).map((s) => s.key),
        );
        const known = new Set(previous.current.recent.map((e) => e.id));
        previous.current = next;
        setFresh(new Set(next.recent.filter((e) => !known.has(e.id)).map((e) => e.id)));
        setBumped(changed);
        setData(next);
        setOffline(false);
      } catch {
        if (!cancelled) setOffline(true);
      }
    };
    const timer = setInterval(poll, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [paused, initial.window]);

  return (
    <div className={styles.stack}>
      <div className={styles.flowBar}>
        <span className={styles.live} aria-live="polite">
          <span
            className={`${styles.dot} ${paused || offline ? styles.dotCrit : `${styles.dotOk} ${styles.dotPulse}`}`}
            aria-hidden="true"
          />
          {paused ? f.paused : f.live} · {fill(f.updatedAt, { time: formatTime(lang, data.until) })}
        </span>
        <button type="button" className={`${styles.btn} ${styles.btnSm}`} onClick={() => setPaused((p) => !p)}>
          {paused ? f.resume : f.pause}
        </button>
        <nav className={styles.filters} aria-label={f.windowLabel}>
          {(["PT1H", "PT24H", "P7D"] as const).map((w) => (
            <Link
              key={w}
              href={`/${lang}/flow?window=${w}`}
              className={styles.filter}
              aria-current={initial.window === w ? "true" : undefined}
            >
              {f.windows[w]}
            </Link>
          ))}
        </nav>
      </div>
      {offline && (
        <p className={styles.message} role="alert">
          {f.offline}
        </p>
      )}

      <div className={styles.gridTwo}>
        <section className={styles.panel} aria-labelledby="pipeline-title">
          <div className={styles.panelHead}>
            <h2 id="pipeline-title">{f.pipeline}</h2>
          </div>
          <ol className={styles.pipeline}>
            {flow.stages.map((stage, index) => {
              const label = f.stages[stage.key];
              const active = !paused && stage.input > 0;
              const bump = bumped.has(stage.key);
              const query = rejectedQuery[stage.key];
              return (
                <li key={stage.key} className={styles.pipelineItem}>
                  {index > 0 && (
                    <span
                      className={`${styles.connector} ${active ? styles.connectorLive : ""} ${bump ? styles.connectorFast : ""}`}
                      aria-hidden="true"
                    />
                  )}
                  <div className={`${styles.stageCard} ${bump ? styles.stageBump : ""}`}>
                    <span className={`${styles.stageMono} ${stage.key === "clearance" ? styles.stageMonoWarn : ""}`}>
                      {monogram[stage.key]}
                    </span>
                    <span className={styles.stageText}>
                      <b>{label.title}</b>
                      <span>{label.sub}</span>
                    </span>
                    <span className={styles.stageNumbers}>
                      <span className={styles.stageCount}>
                        {integer.format(stage.passed)} <small>{f.passed}</small>
                      </span>
                      <span className={styles.stageBadges}>
                        {stage.rejected > 0 && (
                          <Link
                            href={`/${lang}/logs?level=ERROR${query ? `&q=${query}` : ""}`}
                            className={`${styles.sev} ${styles["sev-err"]}`}
                          >
                            {fill(f.rejected, { count: integer.format(stage.rejected) })}
                          </Link>
                        )}
                        {stage.diverted > 0 && (
                          <span className={`${styles.sev} ${styles["sev-info"]}`}>
                            {fill(f.replays, { count: integer.format(stage.diverted) })}
                          </span>
                        )}
                      </span>
                      <span className={styles.stageTime}>
                        {stage.lastAt ? fill(f.lastAt, { time: formatTime(lang, stage.lastAt) }) : f.never}
                      </span>
                    </span>
                  </div>
                </li>
              );
            })}
          </ol>
          <p className={styles.note}>{f.note}</p>
        </section>

        <div className={styles.stack}>
          <section className={styles.panel} aria-labelledby="outcomes-title">
            <div className={styles.panelHead}>
              <h2 id="outcomes-title">{f.outcomes}</h2>
            </div>
            <div className={styles.outcomes}>
              <div>
                <span className={styles.lbl}>{f.cleared}</span>
                <span className={`${styles.big} ${styles.tOk}`}>{integer.format(flow.outcomes.cleared)}</span>
              </div>
              <div>
                <span className={styles.lbl}>{f.allRejected}</span>
                <span className={`${styles.big} ${flow.outcomes.rejected ? styles.tCrit : ""}`}>
                  {integer.format(flow.outcomes.rejected)}
                </span>
                {flow.outcomes.rejected > 0 && <Link href={`/${lang}/logs?level=ERROR`}>{f.seeRejected}</Link>}
              </div>
              <div>
                <span className={styles.lbl}>{f.replaysTitle}</span>
                <span className={styles.big}>{integer.format(flow.outcomes.replays)}</span>
              </div>
            </div>
          </section>

          <section className={styles.panel} aria-labelledby="feed-title">
            <div className={styles.panelHead}>
              <h2 id="feed-title">{f.feed}</h2>
              <span className={styles.hint}>{f.feedHint}</span>
            </div>
            {data.recent.length === 0 ? (
              <p className={styles.empty}>{f.feedEmpty}</p>
            ) : (
              <ul className={styles.feed} aria-live="polite">
                {data.recent.map((e) => (
                  <li key={e.id} className={fresh.has(e.id) ? styles.feedFresh : undefined}>
                    <time className={styles.time} dateTime={e.occurredAt}>
                      {formatTime(lang, e.occurredAt)}
                    </time>
                    <span className={`${styles.sev} ${styles[levelClass[e.level]]}`}>{dict.logs.levels[e.level]}</span>
                    <span className={styles.feedText}>
                      {dict.logs.events[e.event]}
                      {e.invoiceNumber && (
                        <>
                          {" · "}
                          {e.invoiceId ? (
                            <Link href={`/${lang}/invoices/${e.invoiceId}`} className={styles.mono}>
                              {e.invoiceNumber}
                            </Link>
                          ) : (
                            <bdi className={styles.mono}>{e.invoiceNumber}</bdi>
                          )}
                        </>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
