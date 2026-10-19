import type { ReactNode } from "react";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { t } from "@/app/[lang]/dictionaries";
import { formatDateTime, intlTag, type Locale } from "@/lib/i18n";
import type { Quality, WorkOrderStatus } from "@/lib/atelys";
import { atelysAction } from "./actions";
import s from "./atelys.module.css";

/** Shared pieces of the Atelys pages. */

export const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v)?.trim() || undefined;

export function num(lang: Locale, value: number | null | undefined, digits = 3) {
  if (value == null) return "—";
  return new Intl.NumberFormat(intlTag(lang), { maximumFractionDigits: digits, numberingSystem: "latn" }).format(value);
}

export function pct(lang: Locale, ratio: number | null | undefined) {
  if (ratio == null) return "—";
  return new Intl.NumberFormat(intlTag(lang), {
    style: "percent",
    maximumFractionDigits: 1,
    numberingSystem: "latn",
  }).format(ratio);
}

export function Qty({ lang, value, unit }: { lang: Locale; value: number | null | undefined; unit: string }) {
  return (
    <>
      {num(lang, value)}
      <span className={s.unit}>{unit}</span>
    </>
  );
}

export function when(lang: Locale, iso: string | null | undefined) {
  return iso ? formatDateTime(lang, iso) : "—";
}

export function QualityBadge({ quality, dict }: { quality: Quality; dict: Dictionary }) {
  return <span className={`${s.badge} ${s[`q-${quality}`]}`}>{dict.atelys.quality[quality]}</span>;
}

const woTone: Record<WorkOrderStatus, string> = {
  DRAFT: "st-muted",
  PLANNED: "st-info",
  RELEASED: "st-accent",
  IN_PROGRESS: "st-accent",
  ON_HOLD: "st-warn",
  COMPLETED: "st-ok",
  CLOSED: "st-muted",
  CANCELLED: "st-crit",
};

export function WorkOrderBadge({ status, dict }: { status: WorkOrderStatus; dict: Dictionary }) {
  return <span className={`${s.badge} ${s[woTone[status]]}`}>{dict.atelys.woStatus[status]}</span>;
}

export function Badge({ tone, children }: { tone: "ok" | "warn" | "crit" | "info" | "accent" | "muted"; children: ReactNode }) {
  return <span className={`${s.badge} ${s[`st-${tone}`]}`}>{children}</span>;
}

/** Error or success of the last form, from the query string. */
export function Notice({ error, ok, dict }: { error?: string; ok?: string; dict: Dictionary }) {
  const e = dict.atelys.errors;
  if (error) {
    const text = (e as Record<string, string>)[error] ?? t(e.default, { code: error });
    return (
      <p className={`${s.notice} ${s.noticeError}`} role="alert">
        {text}
      </p>
    );
  }
  if (ok) {
    return (
      <p className={`${s.notice} ${s.noticeOk}`} role="status">
        {dict.atelys.common.ok}
      </p>
    );
  }
  return null;
}

export function Head({
  title,
  lead,
  eyebrow,
  children,
}: {
  title: string;
  lead?: string;
  eyebrow?: string;
  children?: ReactNode;
}) {
  return (
    <div className={s.head}>
      <div>
        {eyebrow && <div className={s.eyebrow}>{eyebrow}</div>}
        <h1>{title}</h1>
        {lead && <p>{lead}</p>}
      </div>
      {children && <div className={s.actions}>{children}</div>}
    </div>
  );
}

const LIFECYCLE: WorkOrderStatus[] = ["DRAFT", "PLANNED", "RELEASED", "IN_PROGRESS", "COMPLETED", "CLOSED"];

/** Work order lifecycle as a strip of modules; ON_HOLD sits on IN_PROGRESS, CANCELLED stops where it was left. */
export function Lifecycle({ status, dict }: { status: WorkOrderStatus; dict: Dictionary }) {
  const labels = dict.atelys.woStatus;
  const at = status === "ON_HOLD" ? 3 : LIFECYCLE.indexOf(status);
  return (
    <>
      <ol className={s.steps} aria-label={labels[status]}>
        {LIFECYCLE.map((step, i) => (
          <li
            key={step}
            className={`${s.step} ${i < at ? s.stepDone : i === at ? s.stepNow : ""}`}
            aria-current={i === at ? "step" : undefined}
          >
            {labels[step]}
          </li>
        ))}
      </ol>
      {(status === "ON_HOLD" || status === "CANCELLED") && <p className={s.stepNote}>{labels[status]}</p>}
    </>
  );
}

/** A form posting to the Atelys server action, with its routing fields. */
export function ActionForm({
  lang,
  method = "POST",
  path,
  back,
  next,
  lines,
  list,
  require,
  className,
  children,
}: {
  lang: Locale;
  method?: "POST" | "PUT" | "PATCH";
  path: string;
  back: string;
  next?: string;
  lines?: string;
  list?: boolean;
  require?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <form action={atelysAction} className={className}>
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="_method" value={method} />
      <input type="hidden" name="_path" value={path} />
      <input type="hidden" name="_back" value={back} />
      {next && <input type="hidden" name="_next" value={next} />}
      {lines && <input type="hidden" name="_lines" value={lines} />}
      {list && <input type="hidden" name="_list" value="1" />}
      {require && <input type="hidden" name="_require" value={require} />}
      {children}
    </form>
  );
}

export function ApiDown({ dict, kind }: { dict: Dictionary; kind: string }) {
  return (
    <p className={`${s.notice} ${s.noticeError}`} role="alert">
      {kind === "no-company" ? dict.atelys.common.noCompany : dict.atelys.common.apiError}
    </p>
  );
}

/** Stock split of one product in a single bar; decorative, the figures are in the same row. */
export function StockSplit({
  available,
  reserved,
  blocked,
  inTransit,
}: {
  available: number;
  reserved: number;
  blocked: number;
  inTransit: number;
}) {
  const parts = [
    [Math.max(available, 0), s.splitAvailable],
    [Math.max(reserved, 0), s.splitReserved],
    [Math.max(blocked, 0), s.splitBlocked],
    [Math.max(inTransit, 0), s.splitTransit],
  ] as const;
  const total = parts.reduce((sum, [v]) => sum + v, 0);
  return (
    <div className={s.split} aria-hidden="true">
      {total > 0 && parts.map(([v, cls], i) => (v > 0 ? <span key={i} className={cls} style={{ width: `${(v / total) * 100}%` }} /> : null))}
    </div>
  );
}

/**
 * A partner picked from the environment's list (suppliers or customers), never typed: the same name everywhere.
 * The value sent is the partner's name, which is what receipts and shipments record.
 */
export function PartnerSelect({
  name,
  label,
  partners,
  emptyLabel,
  noneHint,
}: {
  name: string;
  label: string;
  partners: { id: string; name: string }[];
  emptyLabel: string;
  noneHint: string;
}) {
  return (
    <label>
      {label}
      <select name={name} defaultValue="">
        <option value="">{emptyLabel}</option>
        {partners.map((p) => (
          <option key={p.id} value={p.name}>
            {p.name}
          </option>
        ))}
      </select>
      {partners.length === 0 && <span style={{ fontSize: 12, opacity: 0.8 }}>{noneHint}</span>}
    </label>
  );
}
