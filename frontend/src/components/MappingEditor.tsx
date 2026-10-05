"use client";

import { useMemo, useState, useTransition, type DragEvent } from "react";
import { useRouter } from "next/navigation";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import {
  createMappingDraft,
  publishMappingVersion,
  saveMappingDraft,
  testMappingVersion,
} from "@/app/[lang]/clients/mappingActions";
import { formatDateTime, type Locale } from "@/lib/i18n";
import type { FlowMapping, MappingRuleDto, MappingVersionDto, RuleField, RuleTransform } from "@/lib/api";

type Studio = Dictionary["studio"];
type Field = { field: string; reason: string };

const DRAG_TYPE = "application/x-mystix-source";
const CONSTANT = "CONSTANT";

function fill(template: string, values: Record<string, string | number>) {
  return template.replace(/\{(\w+)\}/g, (_, k: string) => String(values[k] ?? ""));
}

function clean(rules: MappingRuleDto[]): MappingRuleDto[] {
  return rules.map((r) => ({
    target: r.target,
    source: r.source ?? null,
    transforms: r.transforms.map((t) => ({
      op: t.op,
      value: t.value ?? null,
      table: t.op === "lookup" ? (t.table ?? {}) : undefined,
      fallback: t.op === "lookup" ? (t.fallback ?? "KEEP") : null,
    })),
  }));
}

/** Drag-and-drop editor of a flow's mapping rules (ADR-0008). */
export function MappingEditor({
  companyId,
  flowId,
  mapping,
  initialVersion,
  studio,
  lang,
}: {
  companyId: string;
  flowId: string;
  mapping: FlowMapping;
  initialVersion: number | null;
  studio: Studio;
  lang: Locale;
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [selected, setSelected] = useState<number | null>(initialVersion ?? mapping.versions[0]?.version ?? null);
  const version: MappingVersionDto | undefined = mapping.versions.find((v) => v.version === selected);
  const [rules, setRules] = useState<MappingRuleDto[]>(() => clean(version?.rules ?? []));
  const [loadedFor, setLoadedFor] = useState<string | undefined>(version?.rulesSha256);
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState<{ tone: "ok" | "err"; text: string; fields?: Field[] } | null>(null);
  const [overTarget, setOverTarget] = useState<string | null>(null);
  const [busy, setBusy] = useState<"save" | "test" | "publish" | "create" | null>(null);

  // Reload the rules when the server data for the selected version changes (after refresh).
  if (version && version.rulesSha256 !== loadedFor && !dirty) {
    setRules(clean(version.rules));
    setLoadedFor(version.rulesSha256);
  }

  const editable = version?.status === "DRAFT";
  const ruleFor = (target: string) => rules.find((r) => r.target === target);
  const headerSources = mapping.catalog.sources.filter((s) => s.scope === "HEADER");
  const lineSources = mapping.catalog.sources.filter((s) => s.scope === "LINE");
  const testedGreen = !!version && version.testPassed === true && version.testedSha256 === version.rulesSha256;
  const reportStale = !!version?.testReport && version.testedSha256 !== version.rulesSha256;

  const update = (target: string, change: (rule: MappingRuleDto) => MappingRuleDto | null) => {
    setRules((current) => {
      const existing = current.find((r) => r.target === target) ?? { target, source: null, transforms: [] };
      const next = change(existing);
      const others = current.filter((r) => r.target !== target);
      return next === null ? others : [...others, next].sort((a, b) => order(a.target) - order(b.target));
    });
    setDirty(true);
    setMessage(null);
  };
  const order = (path: string) => mapping.catalog.targets.findIndex((t) => t.path === path);

  const compatible = (source: string, target: RuleField) =>
    source === CONSTANT || !(target.scope === "HEADER" && source.startsWith("lines."));

  const setSource = (target: RuleField, source: string) => {
    if (!compatible(source, target)) {
      setMessage({ tone: "err", text: studio.lineToHeader });
      return;
    }
    update(target.path, (r) => ({
      ...r,
      source:
        source === ""
          ? null
          : source === CONSTANT
            ? { type: "CONSTANT", value: r.source?.type === "CONSTANT" ? (r.source.value ?? "") : "" }
            : { type: "FIELD", path: source },
    }));
  };

  const after = (outcome: { ok: true; version: number } | { ok: false; errorCode: string; fields?: Field[] }, okText: string) => {
    if (outcome.ok) {
      setSelected(outcome.version);
      setDirty(false);
      setLoadedFor(undefined);
      setMessage({ tone: "ok", text: okText });
      router.refresh();
    } else {
      const text = studio.errors[outcome.errorCode as keyof typeof studio.errors] ?? fill(studio.errors.default, { code: outcome.errorCode });
      setMessage({ tone: "err", text, fields: outcome.fields });
    }
  };

  const act = (kind: "save" | "test" | "publish" | "create", fn: () => Promise<void>) => {
    setBusy(kind);
    startTransition(async () => {
      try {
        await fn();
      } finally {
        setBusy(null);
      }
    });
  };

  const save = () =>
    act("save", async () => {
      if (selected === null) return;
      after(await saveMappingDraft(companyId, flowId, selected, clean(rules)), studio.saved);
    });

  const test = () =>
    act("test", async () => {
      if (selected === null) return;
      if (dirty) {
        const saved = await saveMappingDraft(companyId, flowId, selected, clean(rules));
        if (!saved.ok) return after(saved, studio.saved);
        setDirty(false);
      }
      after(await testMappingVersion(companyId, flowId, selected), studio.report);
    });

  const publish = () =>
    act("publish", async () => {
      if (selected === null) return;
      after(await publishMappingVersion(companyId, flowId, selected), studio.published);
    });

  const createDraft = (from: MappingVersionDto | null) =>
    act("create", async () => {
      after(await createMappingDraft(companyId, flowId, from ? clean(from.rules) : null), studio.saved);
    });

  const sourceChip = (field: RuleField | null) => {
    const path = field ? field.path : CONSTANT;
    return (
      <span
        key={path}
        className={styles.studioChip}
        draggable={editable}
        onDragStart={(e: DragEvent<HTMLSpanElement>) => {
          e.dataTransfer.setData(DRAG_TYPE, path);
          e.dataTransfer.setData("text/plain", path);
          e.dataTransfer.effectAllowed = "copy";
        }}
        aria-disabled={!editable}
      >
        <small>{field ? field.term : "="}</small>
        <span className={styles.mono}>{field ? field.path : studio.constant}</span>
      </span>
    );
  };

  const reportSamples = useMemo(() => version?.testReport?.samples ?? [], [version]);

  return (
    <div className={styles.stack}>
      <div className={styles.flowBar}>
        <nav className={styles.filters} aria-label={studio.versions}>
          {mapping.versions.length === 0 && <span className={styles.muted}>{studio.noVersion}</span>}
          {mapping.versions.map((v) => (
            <button
              key={v.version}
              type="button"
              className={styles.filter}
              aria-pressed={v.version === selected}
              onClick={() => {
                setSelected(v.version);
                setRules(clean(v.rules));
                setLoadedFor(v.rulesSha256);
                setDirty(false);
                setMessage(null);
              }}
            >
              v{v.version} · {studio.status[v.status]}
            </button>
          ))}
        </nav>
        <div className={styles.studioActions}>
          {dirty && <span className={`${styles.sev} ${styles["sev-warn"]}`}>{studio.dirty}</span>}
          {(!version || version.status !== "DRAFT") && (
            <button type="button" className={styles.btn} disabled={pending} onClick={() => createDraft(version ?? null)}>
              {version ? fill(studio.draftFrom, { version: version.version }) : studio.newDraft}
            </button>
          )}
          {version?.status === "RETIRED" && (
            <button type="button" className={styles.btn} disabled={pending} onClick={publish}>
              {studio.rollback}
            </button>
          )}
          {editable && (
            <>
              <button type="button" className={styles.btn} disabled={pending || !dirty} onClick={save}>
                {busy === "save" ? "…" : studio.save}
              </button>
              <button type="button" className={styles.btn} disabled={pending} onClick={test}>
                {busy === "test" ? studio.testing : studio.test}
              </button>
              <button
                type="button"
                className={`${styles.btn} ${styles.btnPrimary}`}
                disabled={pending || dirty || !testedGreen}
                title={testedGreen ? undefined : studio.publishBlocked}
                onClick={publish}
              >
                {fill(studio.publish, { version: version.version })}
              </button>
            </>
          )}
        </div>
      </div>

      {message && (
        <div className={message.tone === "ok" ? styles.notice : styles.message} role={message.tone === "ok" ? "status" : "alert"}>
          {message.text}
          {message.fields && message.fields.length > 0 && (
            <ul className={styles.studioErrors}>
              {message.fields.map((f, i) => (
                <li key={i}>
                  <span className={styles.mono}>{f.field}</span> {f.reason}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {version && !editable && (
        <p className={styles.muted} style={{ margin: 0 }}>
          {fill(studio.readOnly, { status: studio.status[version.status].toLowerCase() })}
        </p>
      )}
      {editable && !testedGreen && <p className={styles.muted} style={{ margin: 0 }}>{studio.publishBlocked}</p>}

      <div className={styles.studio}>
        <aside className={`${styles.panel} ${styles.studioPalette}`} aria-label={studio.sources}>
          <div className={styles.panelHead}>
            <h2>{studio.sources}</h2>
          </div>
          <p className={styles.note} style={{ paddingTop: 10 }}>
            {studio.sourcesHint}
          </p>
          <div className={styles.studioGroup}>
            <span className={styles.studioGroupTitle}>{studio.header}</span>
            {headerSources.map((s) => sourceChip(s))}
          </div>
          <div className={styles.studioGroup}>
            <span className={styles.studioGroupTitle}>{studio.line}</span>
            {lineSources.map((s) => sourceChip(s))}
          </div>
          <div className={styles.studioGroup}>{sourceChip(null)}</div>
        </aside>

        <section className={styles.studioTargets} aria-label={studio.targets}>
          {mapping.catalog.targets.map((target) => {
            const rule = ruleFor(target.path);
            const sourceValue = rule?.source ? (rule.source.type === "CONSTANT" ? CONSTANT : (rule.source.path ?? "")) : "";
            const isOver = overTarget === target.path;
            return (
              <article
                key={target.path}
                className={`${styles.studioTarget} ${rule ? styles.studioTargetActive : ""} ${isOver ? styles.studioTargetOver : ""}`}
                onDragOver={(e) => {
                  if (!editable) return;
                  e.preventDefault();
                  setOverTarget(target.path);
                }}
                onDragLeave={() => setOverTarget((t) => (t === target.path ? null : t))}
                onDrop={(e) => {
                  e.preventDefault();
                  setOverTarget(null);
                  const source = e.dataTransfer.getData(DRAG_TYPE) || e.dataTransfer.getData("text/plain");
                  if (editable && source) setSource(target, source);
                }}
              >
                <header className={styles.studioTargetHead}>
                  <span className={`${styles.kindPill} ${rule ? styles.kindConfigured : styles.kindDirect}`}>{target.term}</span>
                  <b className={styles.mono}>{target.path}</b>
                  <span className={styles.muted}>{target.scope === "LINE" ? studio.line : studio.header}</span>
                  {rule && editable && (
                    <button type="button" className={`${styles.btn} ${styles.btnSm}`} onClick={() => update(target.path, () => null)}>
                      {studio.removeRule}
                    </button>
                  )}
                </header>

                <div className={styles.studioRow}>
                  <label className={styles.field}>
                    <span>{studio.sourceLabel}</span>
                    <select
                      value={sourceValue}
                      disabled={!editable}
                      onChange={(e) => setSource(target, e.target.value)}
                    >
                      <option value="">{studio.keep}</option>
                      {(target.scope === "HEADER" ? headerSources : [...headerSources, ...lineSources]).map((s) => (
                        <option key={s.path} value={s.path}>
                          {s.term} · {s.path}
                        </option>
                      ))}
                      <option value={CONSTANT}>{studio.constant}</option>
                    </select>
                  </label>
                  {rule?.source?.type === "CONSTANT" && (
                    <label className={styles.field}>
                      <span>{studio.constantValue}</span>
                      <input
                        value={rule.source.value ?? ""}
                        maxLength={target.maxLen}
                        disabled={!editable}
                        onChange={(e) => update(target.path, (r) => ({ ...r, source: { type: "CONSTANT", value: e.target.value } }))}
                      />
                    </label>
                  )}
                </div>

                <div className={styles.studioChain}>
                  {(rule?.transforms ?? []).map((t, index) => (
                    <TransformEditor
                      key={index}
                      transform={t}
                      studio={studio}
                      editable={editable}
                      onChange={(next) =>
                        update(target.path, (r) => ({
                          ...r,
                          transforms: next === null ? r.transforms.filter((_, i) => i !== index) : r.transforms.map((x, i) => (i === index ? next : x)),
                        }))
                      }
                    />
                  ))}
                  {editable && (
                    <label className={styles.studioAdd}>
                      <span className={styles.srOnly}>{studio.addTransform}</span>
                      <select
                        value=""
                        onChange={(e) => {
                          const op = e.target.value;
                          if (!op) return;
                          update(target.path, (r) => ({
                            ...r,
                            transforms: [
                              ...r.transforms,
                              op === "lookup" ? { op, table: { "": "" }, fallback: "KEEP" } : { op, value: ["prefix", "suffix", "default"].includes(op) ? "" : null },
                            ],
                          }));
                        }}
                      >
                        <option value="">+ {studio.addTransform}</option>
                        {mapping.catalog.ops.map((op) => (
                          <option key={op} value={op}>
                            {studio.ops[op as keyof typeof studio.ops] ?? op}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                  {!rule && !editable && <span className={styles.muted}>{studio.noRule}</span>}
                </div>
              </article>
            );
          })}
        </section>
      </div>

      <section className={styles.panel} aria-labelledby="report-title">
        <div className={styles.panelHead}>
          <h2 id="report-title">{studio.report}</h2>
          <span className={styles.hint}>{studio.reportHint}</span>
          {version?.testReport && (
            <div className={styles.right}>
              <span className={`${styles.sev} ${version.testReport.passed ? styles["sev-ok"] : styles["sev-err"]}`}>
                {version.testReport.passed
                  ? fill(studio.passed, { total: version.testReport.total })
                  : fill(studio.failed, { failures: version.testReport.failures, total: version.testReport.total })}
              </span>
              <span className={styles.stageTime}>{formatDateTime(lang, version.testReport.testedAt)}</span>
            </div>
          )}
        </div>
        {!version?.testReport && <p className={styles.empty}>{studio.reportNone}</p>}
        {reportStale && <p className={styles.note} style={{ paddingTop: 10 }}>{studio.reportStale}</p>}
        {reportSamples.length > 0 && (
          <ul className={styles.studioSamples}>
            {reportSamples.map((s, i) => (
              <li key={i}>
                <details>
                  <summary className={styles.studioSample}>
                    <span className={`${styles.sev} ${s.passed ? styles["sev-ok"] : styles["sev-err"]}`}>{s.passed ? "OK" : "KO"}</span>
                    <b className={styles.mono}>{s.label === "reference" ? studio.reference : s.label}</b>
                    <span className={styles.muted}>
                      {s.passed ? (s.changes.length ? fill(studio.changes, { count: s.changes.length }) : studio.noChanges) : s.errors[0]}
                    </span>
                  </summary>
                  {!s.passed && (
                    <ul className={styles.studioErrors}>
                      {s.errors.slice(1).map((e, j) => (
                        <li key={j} lang="en">
                          {e}
                        </li>
                      ))}
                    </ul>
                  )}
                  {s.changes.length > 0 && (
                    <div className={styles.tableWrap}>
                      <table className={styles.table}>
                        <thead>
                          <tr>
                            <th scope="col">BT</th>
                            <th scope="col">{studio.sourceLabel}</th>
                            <th scope="col">{studio.before}</th>
                            <th scope="col">{studio.after}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {s.changes.map((c, j) => (
                            <tr key={j}>
                              <td>{c.term ?? "—"}</td>
                              <td>
                                {c.label}
                                {c.line !== null ? ` #${c.line + 1}` : ""}
                              </td>
                              <td className={`${styles.mono} ${styles.tCrit}`}>{c.before ?? "—"}</td>
                              <td className={`${styles.mono} ${styles.tOk}`}>{c.after ?? "—"}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </details>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function TransformEditor({
  transform,
  studio,
  editable,
  onChange,
}: {
  transform: RuleTransform;
  studio: Studio;
  editable: boolean;
  onChange: (next: RuleTransform | null) => void;
}) {
  const label = studio.ops[transform.op as keyof typeof studio.ops] ?? transform.op;
  const entries = Object.entries(transform.table ?? {});
  const setTable = (rows: [string, string][]) => onChange({ ...transform, table: Object.fromEntries(rows) });
  return (
    <div className={styles.studioTransform}>
      <span className={styles.studioTransformHead}>
        <b>{label}</b>
        {editable && (
          <button type="button" className={styles.linkButton} onClick={() => onChange(null)} aria-label={`${studio.remove} ${label}`}>
            ×
          </button>
        )}
      </span>
      {["prefix", "suffix", "default"].includes(transform.op) && (
        <input
          aria-label={`${label} · ${studio.value}`}
          value={transform.value ?? ""}
          disabled={!editable}
          onChange={(e) => onChange({ ...transform, value: e.target.value })}
        />
      )}
      {transform.op === "lookup" && (
        <div className={styles.studioLookup}>
          <span className={styles.muted}>{studio.from}</span>
          <span className={styles.muted}>{studio.to}</span>
          <span />
          {entries.map(([from, to], i) => (
            <LookupRow
              key={i}
              from={from}
              to={to}
              editable={editable}
              studio={studio}
              onChange={(f, t) => setTable(entries.map((row, j) => (j === i ? [f, t] : row)))}
              onRemove={() => setTable(entries.filter((_, j) => j !== i))}
            />
          ))}
          {editable && (
            <button type="button" className={styles.linkButton} onClick={() => setTable([...entries, ["", ""]])}>
              + {studio.addRow}
            </button>
          )}
          <label className={styles.studioFallback}>
            <span>{studio.fallback}</span>
            <select
              value={transform.fallback ?? "KEEP"}
              disabled={!editable}
              onChange={(e) => onChange({ ...transform, fallback: e.target.value })}
            >
              <option value="KEEP">{studio.fallbacks.KEEP}</option>
              <option value="REJECT">{studio.fallbacks.REJECT}</option>
            </select>
          </label>
        </div>
      )}
    </div>
  );
}

function LookupRow({
  from,
  to,
  editable,
  studio,
  onChange,
  onRemove,
}: {
  from: string;
  to: string;
  editable: boolean;
  studio: Studio;
  onChange: (from: string, to: string) => void;
  onRemove: () => void;
}) {
  return (
    <>
      <input aria-label={studio.from} value={from} disabled={!editable} onChange={(e) => onChange(e.target.value, to)} />
      <input aria-label={studio.to} value={to} disabled={!editable} onChange={(e) => onChange(from, e.target.value)} />
      {editable ? (
        <button type="button" className={styles.linkButton} onClick={onRemove} aria-label={studio.remove}>
          ×
        </button>
      ) : (
        <span />
      )}
    </>
  );
}
