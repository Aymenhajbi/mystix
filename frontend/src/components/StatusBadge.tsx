import styles from "@/app/[lang]/portal.module.css";
import { severityOf, type Severity } from "@/lib/invoiceView";

const sevClass: Record<Severity, string> = {
  ok: styles["sev-ok"],
  warn: styles["sev-warn"],
  err: styles["sev-err"],
  info: styles["sev-info"],
};

/** Status in text and color: the meaning never depends on color alone. */
export function StatusBadge({ status, label }: { status: string; label: string }) {
  return <span className={`${styles.sev} ${sevClass[severityOf(status)]}`}>{label}</span>;
}

export function SimulatedBadge({ label }: { label: string }) {
  return <span className={styles.simBadge}>{label}</span>;
}
