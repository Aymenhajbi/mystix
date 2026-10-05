import styles from "@/app/[lang]/portal.module.css";
import { STOCK_STATES, type StockPosition } from "@/lib/api";
import { intlTag, type Locale } from "@/lib/i18n";

/** Quantity with up to 3 decimals (NUMERIC(18,3)), Latin digits in both languages. */
export function formatQuantity(lang: Locale, value: number | null | undefined) {
  if (value === null || value === undefined) return "—";
  return new Intl.NumberFormat(intlTag(lang), { maximumFractionDigits: 3, numberingSystem: "latn" }).format(value);
}

/**
 * Split of one position across its states. Decorative: the numbers are always shown next to it, so the bar is
 * hidden from assistive technologies and colour never carries the meaning alone.
 */
export function StockBar({ position }: { position: StockPosition }) {
  const parts = STOCK_STATES.map((s) => ({ state: s, value: Math.max(0, position.states[s] ?? 0) }));
  const total = parts.reduce((sum, p) => sum + p.value, 0);
  return (
    <span className={styles.stockBar} aria-hidden="true">
      {total > 0 &&
        parts
          .filter((p) => p.value > 0)
          .map((p) => (
            <span key={p.state} className={styles[`st-${p.state}`]} style={{ inlineSize: `${(p.value / total) * 100}%` }} />
          ))}
    </span>
  );
}

export function StockLegend({ labels }: { labels: Record<(typeof STOCK_STATES)[number], string> }) {
  return (
    <span className={styles.legend}>
      {STOCK_STATES.map((s) => (
        <span key={s}>
          <i className={styles[`st-${s}`]} aria-hidden="true" />
          {labels[s]}
        </span>
      ))}
    </span>
  );
}
