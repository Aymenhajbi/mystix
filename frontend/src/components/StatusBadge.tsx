import styles from "@/app/[lang]/portal.module.css";

const tone: Record<string, string> = {
  CLEARED: styles.success,
  CLEARANCE_REJECTED: styles.danger,
  CLEARANCE_ERROR: styles.danger,
  VALIDATED: styles.warning,
};

/** Status shown with text and color: the meaning never depends on color alone. */
export function StatusBadge({ status, label }: { status: string; label: string }) {
  return <span className={`${styles.badge} ${tone[status] ?? styles.neutral}`}>{label}</span>;
}

export function SimulatedBadge({ label }: { label: string }) {
  return <span className={`${styles.badge} ${styles.simulated}`}>{label}</span>;
}
