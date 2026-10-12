import Link from "next/link";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { channelEndpoint, type Channel } from "@/lib/channels";
import { formatDateTime, type Locale } from "@/lib/i18n";

const STATUS_TONE = { ACTIVE: "tOk", DRAFT: "", DISABLED: "tCrit" } as const;

/** Channels of one environment, the same table in Mystix and in Atelys (ADR-0015). */
export function ChannelTable({
  lang,
  dict,
  channels,
  partners,
  href,
}: {
  lang: Locale;
  dict: Dictionary;
  channels: Channel[];
  partners: Map<string, string>;
  href: (id: string) => string;
}) {
  const c = dict.channels;
  if (channels.length === 0) return <p className={styles.empty}>{c.empty}</p>;
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>{c.cols.name}</th>
            <th>{c.cols.protocol}</th>
            <th>{c.cols.direction}</th>
            <th>{c.cols.endpoint}</th>
            <th>{c.cols.apps}</th>
            <th>{c.cols.status}</th>
            <th>{c.cols.updated}</th>
          </tr>
        </thead>
        <tbody>
          {channels.map((ch) => (
            <tr key={ch.id}>
              <td>
                <Link href={href(ch.id)} className={styles.strongLink}>
                  {ch.name}
                </Link>
                {ch.partnerId && partners.get(ch.partnerId) && (
                  <div className={styles.muted} style={{ fontSize: 12 }}>
                    {partners.get(ch.partnerId)}
                  </div>
                )}
              </td>
              <td>
                <span className={`${styles.chip} ${styles.chipNeutral}`}>{ch.protocol}</span>
              </td>
              <td>{c.directions[ch.direction]}</td>
              <td className={styles.mono} dir="ltr" style={{ fontSize: 12.5 }}>
                {channelEndpoint(ch)}
              </td>
              <td>{[ch.usedByMystix && "Mystix", ch.usedByAtelys && "Atelys"].filter(Boolean).join(" · ")}</td>
              <td className={STATUS_TONE[ch.status] ? styles[STATUS_TONE[ch.status]] : undefined}>{c.statuses[ch.status]}</td>
              <td className={styles.muted}>{formatDateTime(lang, ch.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Error and success messages of the channel screens, from the query string. */
export function ChannelNotice({ dict, query }: { dict: Dictionary; query: Record<string, string | string[] | undefined> }) {
  const c = dict.channels;
  const one = (k: string) => (Array.isArray(query[k]) ? query[k]?.[0] : query[k]) as string | undefined;
  const error = one("error");
  if (error) {
    const text = c.errors[error as keyof typeof c.errors] ?? c.errors.default.replace("{code}", error);
    return (
      <p className={styles.message} role="alert">
        {text}
      </p>
    );
  }
  const ok = one("created") ? c.created : one("saved") ? c.saved : one("deleted") ? c.deleted : null;
  return ok ? (
    <p className={styles.notice} role="status">
      {ok}
    </p>
  ) : null;
}
