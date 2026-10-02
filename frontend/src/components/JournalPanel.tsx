"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import styles from "@/app/[lang]/portal.module.css";
import { Icon } from "./Icons";
import { SimulatedBadge } from "./StatusBadge";

export type JournalRow = {
  id: string;
  href: string;
  time: string;
  dateTime: string;
  severity: "ok" | "warn" | "err" | "info";
  severityLabel: string;
  stage: string;
  event: string;
  detail: string;
  reference: string | null;
  simulated: boolean;
  search: string;
};

type Labels = {
  title: string;
  hint: string;
  all: string;
  filterLabel: string;
  search: string;
  empty: string;
  foot: string;
  seeAll: string;
  simulated: string;
  col: { time: string; severity: string; process: string; stage: string; event: string; reference: string };
  process: string;
  severities: Record<"ok" | "warn" | "err", string>;
};

const sevClass = { ok: "sev-ok", warn: "sev-warn", err: "sev-err", info: "sev-info" } as const;

/** Journal panel of the Cockpit: state filter chips with counts, text filter, clickable rows. */
export function JournalPanel({ rows, labels, allHref }: { rows: JournalRow[]; labels: Labels; allHref: string }) {
  const router = useRouter();
  const [filter, setFilter] = useState<"all" | "ok" | "warn" | "err">("all");
  const [query, setQuery] = useState("");

  const counts = useMemo(
    () => ({
      ok: rows.filter((r) => r.severity === "ok").length,
      warn: rows.filter((r) => r.severity === "warn").length,
      err: rows.filter((r) => r.severity === "err").length,
    }),
    [rows],
  );

  const visible = rows.filter(
    (r) =>
      (filter === "all" || r.severity === filter) &&
      (!query.trim() || r.search.toLowerCase().includes(query.trim().toLowerCase())),
  );

  const chips: ["all" | "ok" | "warn" | "err", string, number][] = [
    ["all", labels.all, rows.length],
    ["err", labels.severities.err, counts.err],
    ["warn", labels.severities.warn, counts.warn],
    ["ok", labels.severities.ok, counts.ok],
  ];

  return (
    <section className={styles.panel} aria-labelledby="journal-title">
      <div className={styles.panelHead}>
        <h2 id="journal-title">{labels.title}</h2>
        <span className={styles.hint}>{labels.hint}</span>
      </div>
      <div className={styles.bar}>
        <div className={styles.filters} role="group" aria-label={labels.filterLabel}>
          {chips.map(([value, label, count]) => (
            <button
              key={value}
              type="button"
              className={styles.filter}
              aria-pressed={filter === value}
              onClick={() => setFilter(value)}
            >
              {label} <span className={styles.count}>· {count}</span>
            </button>
          ))}
        </div>
        <label className={styles.searchBox}>
          <Icon name="search" size={15} />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={labels.search} />
        </label>
      </div>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">{labels.col.time}</th>
              <th scope="col">{labels.col.severity}</th>
              <th scope="col">{labels.col.stage}</th>
              <th scope="col">{labels.col.event}</th>
              <th scope="col">{labels.col.reference}</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 && (
              <tr>
                <td colSpan={5} className={styles.empty}>
                  {labels.empty}
                </td>
              </tr>
            )}
            {visible.map((r) => (
              <tr key={r.id} className={styles.rowLink} onClick={() => router.push(r.href)}>
                <td>
                  <time className={styles.time} dateTime={r.dateTime}>
                    {r.time}
                  </time>
                </td>
                <td>
                  <span className={`${styles.sev} ${styles[sevClass[r.severity]]}`}>{r.severityLabel}</span>
                </td>
                <td>
                  <span className={styles.stage}>{r.stage}</span>
                </td>
                <td className={styles.wrapCell}>
                  <span className={styles.ev}>
                    <Link href={r.href} className={styles.strongLink} onClick={(e) => e.stopPropagation()}>
                      {r.event}
                    </Link>
                    <span>{r.detail}</span>
                  </span>
                </td>
                <td>
                  {r.reference ? (
                    <span className={styles.ref}>{r.reference}</span>
                  ) : (
                    <span className={styles.muted}>—</span>
                  )}{" "}
                  {r.simulated && <SimulatedBadge label={labels.simulated} />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className={styles.foot}>
        <span>{labels.foot}</span>
        <Link href={allHref}>{labels.seeAll}</Link>
      </div>
    </section>
  );
}
