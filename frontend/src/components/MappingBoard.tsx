"use client";

import { Fragment, useMemo, useState } from "react";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { LineageRow, MappingKind } from "@/lib/api";
import { Icon } from "./Icons";

type Filter = "all" | "transformed" | "notEmitted";

const kindClass: Record<MappingKind, string> = {
  DIRECT: "kindDirect",
  CONSTANT: "kindConstant",
  CALCULATED: "kindCalculated",
  CONFIGURED: "kindConfigured",
  NOT_EMITTED: "kindNotEmitted",
};

/** /inv:Invoice/cac:AccountingSupplierParty/cac:Party/cbc:X → AccountingSupplierParty/Party/X */
function shortXPath(path: string) {
  return path.replace(/^\/inv:Invoice\/?/, "").replace(/\b(cac|cbc):/g, "") || "Invoice";
}

function Wire({ active, kind, from = true }: { active: boolean; kind: MappingKind; from?: boolean }) {
  if (!from) return <span className={styles.wireEmpty} aria-hidden="true" />;
  return (
    <svg className={styles.wire} viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden="true">
      <path d="M0 12 C 40 12, 60 12, 100 12" className={styles.wireBase} />
      <path
        d="M0 12 C 40 12, 60 12, 100 12"
        className={`${styles.wireFlow} ${styles[kindClass[kind]]} ${active ? styles.wireActive : ""}`}
      />
    </svg>
  );
}

/** Read-only, MapForce-like view of the implemented mapping with the real values of one invoice. */
/** {@code structureOnly}: no invoice yet, so paths are shown without values. */
export function MappingBoard({
  rows,
  dict,
  structureOnly = false,
}: {
  rows: LineageRow[];
  dict: Pick<Dictionary, "mapping">;
  structureOnly?: boolean;
}) {
  const m = dict.mapping;
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<string | null>(null);

  const keyOf = (r: LineageRow) => `${r.group}-${r.line ?? ""}-${r.term ?? r.label}`;

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((r) => {
      if (filter === "transformed" && r.kind === "DIRECT") return false;
      if (filter === "notEmitted" && r.kind !== "NOT_EMITTED") return false;
      if (!q) return true;
      return [r.term, r.label, r.request?.path, r.canonical?.path, r.target?.path]
        .filter(Boolean)
        .some((v) => (v as string).toLowerCase().includes(q));
    });
  }, [rows, filter, query]);

  const groupTitle = (r: LineageRow) =>
    r.group === "line" ? m.groups.line.replace("{n}", String((r.line ?? 0) + 1)) : m.groups[r.group];

  return (
    <section className={styles.panel} aria-label={m.title}>
      <div className={styles.bar}>
        <div className={styles.filters} role="group" aria-label={m.filterLabel}>
          {(["all", "transformed", "notEmitted"] as Filter[]).map((f) => (
            <button
              key={f}
              type="button"
              className={styles.filter}
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
            >
              {m.filters[f]}
            </button>
          ))}
        </div>
        <div className={styles.kindLegend} aria-hidden="true">
          {(Object.keys(kindClass) as MappingKind[]).map((k) => (
            <span key={k} className={`${styles.kindPill} ${styles[kindClass[k]]}`}>
              {m.kinds[k]}
            </span>
          ))}
        </div>
        <label className={styles.searchBox}>
          <Icon name="search" size={15} />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={m.search} />
        </label>
      </div>

      <div className={styles.captionRow}>
        <span>{m.count.replace("{shown}", String(visible.length)).replace("{total}", String(rows.length))}</span>
      </div>

      <div className={styles.mapGrid}>
        <div className={styles.mapHead}>
          <span>{m.columns.request}</span>
          <span aria-hidden="true" />
          <span>{m.columns.canonical}</span>
          <span aria-hidden="true" />
          <span>{m.columns.target}</span>
        </div>

        {visible.map((r, index) => {
          const key = keyOf(r);
          const group = groupTitle(r);
          const showGroup = index === 0 || groupTitle(visible[index - 1]) !== group;
          const isOpen = open === key;
          const changed =
            r.request?.value != null && r.target?.value != null && r.request.value !== r.target.value;
          return (
            <Fragment key={key}>
              {showGroup && (
                <div className={styles.mapGroup}>
                  <span>{group}</span>
                </div>
              )}
              <button
                type="button"
                className={`${styles.mapRow} ${isOpen ? styles.mapRowOpen : ""}`}
                onClick={() => setOpen(isOpen ? null : key)}
                aria-expanded={isOpen}
               
              >
                <span className={styles.mapCell}>
                  {r.request ? (
                    <>
                      <span className={styles.mapPath}>{r.request.path}</span>
                      <span className={styles.mapValue}>{structureOnly ? null : (r.request.value ?? <i>{m.absent}</i>)}</span>
                    </>
                  ) : (
                    <span className={styles.mapNone}>—</span>
                  )}
                </span>
                <Wire active={isOpen} kind={r.kind} from={r.request !== null && r.canonical !== null} />
                <span className={styles.mapCell}>
                  {r.canonical ? (
                    <>
                      <span className={styles.mapPath}>{r.canonical.path.replace(/^invoice\./, "")}</span>
                      <span className={styles.mapValue}>{structureOnly ? null : (r.canonical.value ?? <i>{m.absent}</i>)}</span>
                    </>
                  ) : (
                    <span className={styles.mapNone}>—</span>
                  )}
                </span>
                <span className={styles.mapLink}>
                  <Wire active={isOpen} kind={r.kind} from={r.kind !== "NOT_EMITTED"} />
                  <span className={`${styles.kindPill} ${styles[kindClass[r.kind]]}`}>
                    {r.term ? `${r.term} · ` : ""}
                    {m.kinds[r.kind]}
                  </span>
                </span>
                <span className={`${styles.mapCell} ${r.kind === "NOT_EMITTED" ? styles.mapCellDashed : ""}`}>
                  {r.target ? (
                    <>
                      <span className={styles.mapPath}>{shortXPath(r.target.path)}</span>
                      <span className={styles.mapValue}>
                        {structureOnly ? null : (r.target.value ?? <i>{m.absent}</i>)}
                        {changed && <span className={styles.mapChanged}>{m.changed}</span>}
                      </span>
                    </>
                  ) : (
                    <>
                      <span className={styles.mapPath}>{r.label}</span>
                      <span className={styles.mapNone}>{m.notEmitted}</span>
                    </>
                  )}
                </span>
              </button>
              {isOpen && (
                <div className={styles.mapDetails}>
                  <dl className={styles.logMeta}>
                    <dt>{m.term}</dt>
                    <dd>
                      {r.term ?? "—"} · {r.label}
                    </dd>
                    <dt>{m.rule}</dt>
                    <dd>{r.rule}</dd>
                    {r.request && (
                      <>
                        <dt>{m.requestPath}</dt>
                        <dd className={styles.mono}>{r.request.path}</dd>
                      </>
                    )}
                    {r.canonical && (
                      <>
                        <dt>{m.canonicalPath}</dt>
                        <dd className={styles.mono}>{r.canonical.path}</dd>
                      </>
                    )}
                    {r.target && (
                      <>
                        <dt>{m.targetPath}</dt>
                        <dd className={styles.mono}>{r.target.path}</dd>
                      </>
                    )}
                  </dl>
                </div>
              )}
            </Fragment>
          );
        })}
      </div>
    </section>
  );
}
