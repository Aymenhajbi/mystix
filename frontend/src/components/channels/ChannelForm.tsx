"use client";

import { useState } from "react";
import styles from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { deleteChannelAction, saveChannelAction } from "@/lib/channelActions";
import type { Channel, ChannelCatalog, ChannelField, ChannelProtocol } from "@/lib/channels";

type Labels = Dictionary["channels"];

const INPUT_TYPE: Partial<Record<ChannelField["kind"], string>> = {
  PORT: "number",
  SECONDS: "number",
  URL: "url",
  HTTPS_URL: "url",
};

/**
 * Create or edit a communication channel (ADR-0015). The protocol settings come from the backend catalog, so a new
 * parameter appears here without a frontend change. Secrets are never typed: only the alias in the secret store.
 */
export function ChannelForm({
  lang,
  labels,
  catalog,
  partners,
  channel,
  companyId,
  base,
  back,
  defaultApp,
}: {
  lang: string;
  labels: Labels;
  catalog: ChannelCatalog;
  partners: { id: string; name: string }[];
  channel?: Channel;
  companyId?: string;
  base: string;
  back: string;
  defaultApp: "MYSTIX" | "ATELYS";
}) {
  const [protocol, setProtocol] = useState<ChannelProtocol>(channel?.protocol ?? "SFTP");
  const spec = catalog.protocols.find((p) => p.code === protocol) ?? catalog.protocols[0];
  const f = labels.form;
  const fieldLabel = (key: string) => labels.fields[key as keyof Labels["fields"]] ?? key;
  const current = (field: ChannelField) => (channel && channel.protocol === protocol ? channel.settings[field.key] : undefined);
  const hidden = (
    <>
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="_base" value={base} />
      <input type="hidden" name="_back" value={back} />
      {companyId && <input type="hidden" name="companyId" value={companyId} />}
      {channel && <input type="hidden" name="id" value={channel.id} />}
    </>
  );

  return (
    <>
      <form action={saveChannelAction} className={styles.form}>
        {hidden}
        <h3 style={{ margin: 0, fontSize: 14 }}>{f.general}</h3>
        <div className={styles.formRow}>
          <label className={styles.field}>
            <span>{f.name}</span>
            <input name="name" required maxLength={120} defaultValue={channel?.name} />
          </label>
          <label className={styles.field}>
            <span>{f.protocol}</span>
            <select name="protocol" value={protocol} onChange={(e) => setProtocol(e.target.value as ChannelProtocol)}>
              {catalog.protocols.map((p) => (
                <option key={p.code} value={p.code}>
                  {p.code}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field}>
            <span>{f.direction}</span>
            <select name="direction" defaultValue={channel?.direction ?? "IN"}>
              {catalog.directions.map((d) => (
                <option key={d} value={d}>
                  {labels.directions[d]}
                </option>
              ))}
            </select>
          </label>
        </div>
        <p className={styles.formNote}>{labels.protocolHints[protocol]}</p>
        <div className={styles.formRow}>
          <label className={styles.field}>
            <span>{f.partner}</span>
            <select name="partnerId" defaultValue={channel?.partnerId ?? ""}>
              <option value="">{f.noPartner}</option>
              {partners.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field}>
            <span>{f.status}</span>
            <select name="status" defaultValue={channel?.status ?? "DRAFT"}>
              {catalog.statuses.map((s) => (
                <option key={s} value={s}>
                  {labels.statuses[s]}
                </option>
              ))}
            </select>
          </label>
        </div>
        <fieldset className={styles.check} style={{ border: 0, padding: 0, margin: 0, flexWrap: "wrap", gap: 16 }}>
          <legend className={styles.lbl} style={{ marginBottom: 6 }}>
            {f.apps}
          </legend>
          <label style={{ display: "inline-flex", gap: 8, alignItems: "center" }}>
            <input
              type="checkbox"
              name="usedByMystix"
              defaultChecked={channel ? channel.usedByMystix : defaultApp === "MYSTIX"}
            />
            {f.mystix}
          </label>
          <label style={{ display: "inline-flex", gap: 8, alignItems: "center" }}>
            <input
              type="checkbox"
              name="usedByAtelys"
              defaultChecked={channel ? channel.usedByAtelys : defaultApp === "ATELYS"}
            />
            {f.atelys}
          </label>
        </fieldset>

        <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>
          {f.settings} · {protocol}
        </h3>
        <div className={styles.formRow} style={{ flexWrap: "wrap" }} key={protocol}>
          {spec.fields.map((field) => {
            const value = current(field);
            const placeholder =
              field.defaultValue !== null && field.defaultValue !== undefined
                ? labels.form.defaultValue.replace("{value}", String(field.defaultValue))
                : undefined;
            return (
              <label key={field.key} className={styles.field} style={{ minWidth: 220 }}>
                <span>
                  {fieldLabel(field.key)}
                  {field.required && <span aria-hidden="true"> *</span>}
                </span>
                {field.kind === "CHOICE" ? (
                  <select name={`s.${field.key}`} defaultValue={value !== undefined ? String(value) : String(field.defaultValue ?? "")}>
                    {field.choices.map((choice) => (
                      <option key={choice} value={choice}>
                        {choice}
                      </option>
                    ))}
                  </select>
                ) : field.kind === "FLAG" ? (
                  <select name={`s.${field.key}`} defaultValue={value !== undefined ? String(value) : String(field.defaultValue ?? "false")}>
                    <option value="true">{f.yes}</option>
                    <option value="false">{f.no}</option>
                  </select>
                ) : (
                  <input
                    name={`s.${field.key}`}
                    type={INPUT_TYPE[field.kind] ?? "text"}
                    required={field.required}
                    defaultValue={value !== undefined ? String(value) : ""}
                    placeholder={placeholder}
                    dir="ltr"
                    autoComplete="off"
                    spellCheck={false}
                  />
                )}
              </label>
            );
          })}
        </div>

        <h3 style={{ margin: "8px 0 0", fontSize: 14 }}>{f.security}</h3>
        <label className={styles.field}>
          <span>
            {f.credentialRef}
            {spec.needsCredential && <small className={styles.muted}> · {f.credentialNeeded}</small>}
          </span>
          <input
            name="credentialRef"
            maxLength={120}
            pattern="[A-Za-z0-9._:/\-]{1,120}"
            defaultValue={channel?.credentialRef ?? ""}
            dir="ltr"
            autoComplete="off"
            spellCheck={false}
            aria-describedby="credential-help"
          />
          <small id="credential-help" className={styles.muted}>
            {f.credentialHelp}
          </small>
        </label>
        <div className={styles.formActions}>
          <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`}>
            {channel ? f.save : f.create}
          </button>
        </div>
      </form>

      {channel && (
        <form
          action={deleteChannelAction}
          className={styles.formActions}
          onSubmit={(e) => {
            if (!window.confirm(f.deleteConfirm)) e.preventDefault();
          }}
        >
          {hidden}
          <button type="submit" className={`${styles.btn} ${styles.btnDanger}`}>
            {f.delete}
          </button>
        </form>
      )}
    </>
  );
}
