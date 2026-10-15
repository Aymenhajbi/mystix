import { t, type Dictionary } from "@/app/[lang]/dictionaries";
import styles from "@/app/[lang]/portal.module.css";

/** Outcome of a start, a stop or a creation, from the query the server action redirected with. */
export function LifecycleNotice({
  dict,
  query,
}: {
  dict: Dictionary;
  query: { started?: string; stopped?: string; entryPoint?: string; error?: string; why?: string; polled?: string };
}) {
  const c = dict.connectivity;
  if (query.error) {
    const why = (query.why ?? "").split(",").filter(Boolean);
    return (
      <div className={styles.message} role="alert">
        {c.errors[query.error as keyof typeof c.errors] ?? c.errors.default}
        {why.length > 0 && (
          <ul style={{ margin: "6px 0 0", paddingInlineStart: 20 }}>
            {why.map((field) => (
              <li key={field}>{c.why[field as keyof typeof c.why] ?? field}</li>
            ))}
          </ul>
        )}
      </div>
    );
  }
  if (query.polled) {
    const [accepted, rejected, refused, duplicates, skipped] = query.polled.split("-").map((v) => Number(v) || 0);
    return (
      <p className={styles.notice} role="status">
        {t(c.notices.polled, { accepted, rejected, refused, duplicates, skipped })}
      </p>
    );
  }
  const notice = query.started ? c.notices.started : query.stopped ? c.notices.stopped : query.entryPoint ? c.notices.entryPoint : null;
  return notice ? (
    <p className={styles.notice} role="status">
      {notice}
    </p>
  ) : null;
}
