import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { ExchangeFlow } from "@/lib/api";

const statusClass = { ACTIVE: "sev-ok", PAUSED: "sev-warn", DRAFT: "sev-info" } as const;

export function FlowStatusBadge({ status, dict }: { status: ExchangeFlow["status"]; dict: Pick<Dictionary, "clients"> }) {
  return <span className={`${styles.sev} ${styles[statusClass[status]]}`}>{dict.clients.status[status]}</span>;
}

/** Source → mapping → target, as chips joined by a flowing connector. */
export function FlowRoute({ flow, dict, live }: { flow: ExchangeFlow; dict: Pick<Dictionary, "clients">; live: boolean }) {
  const c = dict.clients;
  const label = (map: Record<string, string>, code: string) => map[code] ?? code;
  return (
    <span className={styles.route}>
      <span className={styles.routeChip}>
        <small>{label(c.channels, flow.sourceChannel)}</small>
        {label(c.formats, flow.sourceFormat)}
      </span>
      <span className={`${styles.routeLink} ${live ? styles.routeLinkLive : ""}`} aria-hidden="true" />
      <span className={`${styles.routeChip} ${styles.routeChipMap}`}>
        <small>{c.flow.mapping}</small>
        <span className={styles.mono}>
          {flow.mappingId} {flow.mappingVersion}
        </span>
      </span>
      <span className={`${styles.routeLink} ${live ? styles.routeLinkLive : ""}`} aria-hidden="true" />
      <span className={styles.routeChip}>
        <small>{label(c.channels, flow.targetChannel)}</small>
        {label(c.formats, flow.targetFormat)}
      </span>
    </span>
  );
}
