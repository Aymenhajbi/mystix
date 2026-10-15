"use client";

import Link from "next/link";
import { useActionState } from "react";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import portal from "@/app/[lang]/portal.module.css";
import styles from "@/components/channels/channels.module.css";
import { createListenerAction, type ListenerActionResult } from "@/lib/connectivityActions";
import { OneTimeKey } from "./OneTimeKey";

type Option = { id: string; label: string };

/**
 * New HTTP inbound listener. Saving starts nothing; on success the API key is shown once, here, and never again.
 */
export function ListenerForm({
  lang,
  dict,
  kind,
  channels,
  entryPoints,
}: {
  lang: string;
  dict: Dictionary;
  kind: "HTTP_INBOUND" | "SFTP_POLLING" | "AS2_SERVER";
  channels: Option[];
  entryPoints: Option[];
}) {
  const sftp = kind === "SFTP_POLLING";
  const as2 = kind === "AS2_SERVER";
  const c = dict.connectivity;
  const f = c.form;
  const [state, action, pending] = useActionState<ListenerActionResult | null, FormData>(createListenerAction, null);

  if (state?.ok) {
    return (
      <section className={portal.panel} style={{ padding: 20 }}>
        <p className={portal.notice} role="status">{f.created}</p>
        {state.apiKey && <OneTimeKey title={f.keyTitle} lead={f.keyLead} value={state.apiKey} copyLabel={f.copy} copiedLabel={f.copied} />}
        <p style={{ marginTop: 16 }}>
          <Link href={`/${lang}/connectivity/listeners/${state.id}`} className={`${portal.btn} ${portal.btnPrimary}`}>
            {f.openListener}
          </Link>
        </p>
      </section>
    );
  }

  const errors = new Map((state && !state.ok ? state.fieldErrors : []).map((e) => [e.field, e.reason]));
  return (
    <form action={action} className={portal.panel} style={{ padding: 20 }} noValidate={false}>
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="kind" value={kind} />
      {state && !state.ok && (
        <p className={styles.alert} role="alert">
          {c.errors[state.errorCode as keyof typeof c.errors] ?? c.errors.default}
        </p>
      )}
      <div className={styles.fields}>
        <label className={styles.field} data-invalid={errors.has("name") || undefined}>
          <span>{f.name}<span className={styles.req} aria-hidden="true"> *</span></span>
          <input name="name" required maxLength={120} autoComplete="off" />
          {errors.has("name") && <span className={styles.error}>{errors.get("name")}</span>}
        </label>
        <label className={styles.field} data-invalid={errors.has("channelId") || undefined}>
          <span>{f.channel}<span className={styles.req} aria-hidden="true"> *</span></span>
          <select name="channelId" required defaultValue="">
            <option value="" disabled>—</option>
            {channels.map((o) => (
              <option key={o.id} value={o.id}>{o.label}</option>
            ))}
          </select>
          <span className={styles.hint}>
            {channels.length === 0
              ? sftp ? f.noSftpChannel : as2 ? f.noAs2Channel : f.noChannel
              : sftp ? f.sftpChannelHint : as2 ? f.as2ChannelHint : f.channelHint}
          </span>
        </label>
        <label className={styles.field} data-invalid={errors.has("entryPointId") || undefined}>
          <span>{f.entryPoint}<span className={styles.req} aria-hidden="true"> *</span></span>
          <select name="entryPointId" required defaultValue="">
            <option value="" disabled>—</option>
            {entryPoints.map((o) => (
              <option key={o.id} value={o.id}>{o.label}</option>
            ))}
          </select>
          {entryPoints.length === 0 && <span className={styles.hint}>{f.noEntryPointHint}</span>}
        </label>
        <label className={styles.field} data-invalid={errors.has("settings.maxBytes") || undefined}>
          <span>{f.maxKb}</span>
          <input name="maxKb" type="number" min={1} max={20480} inputMode="numeric" />
          <span className={styles.hint}>{f.maxKbHint}</span>
        </label>
      </div>
      <div className={styles.footer}>
        <Link href={`/${lang}/connectivity`} className={portal.btn}>{f.cancel}</Link>
        <button type="submit" className={`${portal.btn} ${portal.btnPrimary}`} disabled={pending}>
          {pending ? f.creating : f.create}
        </button>
      </div>
    </form>
  );
}
