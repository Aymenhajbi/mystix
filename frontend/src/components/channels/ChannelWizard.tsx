"use client";

import { useRef, useState, useTransition, type FormEvent } from "react";
import portal from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { saveChannel } from "@/lib/channelActions";
import type { Channel, ChannelCatalog, ChannelField, ChannelProtocol } from "@/lib/channels";
import styles from "./channels.module.css";

type Labels = Dictionary["channels"];
type Step = 0 | 1 | 2;

const INPUT_TYPE: Partial<Record<ChannelField["kind"], string>> = {
  PORT: "number",
  SECONDS: "number",
  URL: "url",
  HTTPS_URL: "url",
};

/** Which step shows a form field, so a server error opens the step where it must be fixed. */
function stepOf(field: string, catalogFields: ChannelField[]): Step {
  if (["name", "protocol", "direction", "partnerId", "usedByMystix", "usedByAtelys"].includes(field)) return 0;
  if (field === "credentialRef" || field === "status") return 2;
  const key = field.startsWith("settings.") ? field.slice(9) : field;
  const spec = catalogFields.find((f) => f.key === key);
  return spec && spec.group === "SECURITY" ? 2 : 1;
}

/**
 * Create or edit a communication channel in three steps (ADR-0015): protocol, connection, security. One form, never
 * unmounted: going back and forth keeps what was typed, and a refusal from the backend shows each error at its field,
 * on the step that holds it. The protocol parameters come from the backend catalog.
 */
export function ChannelWizard({
  lang,
  labels,
  catalog,
  partners,
  channel,
  companyId,
  base,
  defaultApp,
}: {
  lang: string;
  labels: Labels;
  catalog: ChannelCatalog;
  partners: { id: string; name: string }[];
  channel?: Channel;
  companyId?: string;
  base: string;
  defaultApp: "MYSTIX" | "ATELYS";
}) {
  const [step, setStep] = useState<Step>(channel ? 1 : 0);
  const [reached, setReached] = useState<Step>(channel ? 2 : 0);
  const [protocol, setProtocol] = useState<ChannelProtocol>(channel?.protocol ?? "SFTP");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [alert, setAlert] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const form = useRef<HTMLFormElement>(null);
  const pane0 = useRef<HTMLFieldSetElement>(null);
  const pane1 = useRef<HTMLFieldSetElement>(null);
  const pane2 = useRef<HTMLFieldSetElement>(null);
  // Only read in event handlers.
  const pane = (s: Step) => (s === 0 ? pane0 : s === 1 ? pane1 : pane2).current;
  const f = labels.form;
  const spec = catalog.protocols.find((p) => p.code === protocol) ?? catalog.protocols[0];
  const fieldLabel = (key: string) =>
    protocol === "AS2" && key === "url" ? labels.fields.as2Url : labels.fields[key as keyof Labels["fields"]] ?? key;
  const stored = (field: ChannelField) => (channel && channel.protocol === protocol ? channel.settings[field.key] : undefined);
  const stepErrors = (s: Step) => Object.keys(errors).some((k) => stepOf(k, spec.fields) === s);

  const go = (target: Step) => {
    // Moving forward checks the fields of the current step first, with the browser's own messages.
    if (target > step && !reportStep(step)) return;
    setStep(target);
    setReached((r) => (target > r ? target : r));
  };

  /** Shows the browser message on the first invalid field of a step; false when the step has one. */
  const reportStep = (s: Step) => {
    const invalid = pane(s)?.querySelector<HTMLInputElement>(":invalid");
    if (!invalid) return true;
    invalid.reportValidity();
    invalid.focus();
    return false;
  };

  const message = (field: ChannelField | undefined, reason: string) => {
    if (/required/i.test(reason)) return labels.required;
    return field ? labels.kindHints[field.kind] : reason;
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (step < 2) {
      go((step + 1) as Step);
      return;
    }
    // The form skips the browser check (hidden steps cannot show it): check each step, open the first one in error.
    const broken = ([0, 1, 2] as Step[]).find((s) => pane(s)?.querySelector(":invalid"));
    if (broken !== undefined) {
      setStep(broken);
      window.setTimeout(() => reportStep(broken), 0);
      return;
    }
    const data = new FormData(event.currentTarget);
    startTransition(async () => {
      const result = await saveChannel(data);
      // On success the action navigates to the channel sheet; we only get here on a refusal.
      const next: Record<string, string> = {};
      for (const e of result.fieldErrors) {
        const key = e.field.startsWith("settings.") ? e.field.slice(9) : e.field;
        next[e.field] = message(spec.fields.find((x) => x.key === key), e.reason);
      }
      setErrors(next);
      const first = result.fieldErrors[0];
      setAlert(
        first ? labels.fieldErrors : labels.errors[result.errorCode as keyof Labels["errors"]] ?? labels.errors.default.replace("{code}", result.errorCode),
      );
      if (first) setStep(stepOf(first.field, spec.fields));
    });
  };

  const settingField = (field: ChannelField) => {
    const value = stored(field);
    const error = errors[`settings.${field.key}`];
    const id = `s-${field.key}`;
    const help = field.defaultValue !== null && field.defaultValue !== undefined && field.kind !== "CHOICE" && field.kind !== "FLAG"
      ? `${f.defaultValue.replace("{value}", String(field.defaultValue))}`
      : null;
    return (
      <label key={field.key} className={styles.field} data-invalid={error ? "true" : undefined}>
        <span>
          {fieldLabel(field.key)}
          {field.required && <span className={styles.req} aria-hidden="true"> *</span>}
        </span>
        {field.kind === "CHOICE" || field.kind === "FLAG" ? (
          <select
            id={id}
            name={`s.${field.key}`}
            defaultValue={value !== undefined ? String(value) : String(field.defaultValue ?? (field.kind === "FLAG" ? "false" : ""))}
            aria-invalid={error ? true : undefined}
          >
            {field.kind === "FLAG" ? (
              <>
                <option value="true">{f.yes}</option>
                <option value="false">{f.no}</option>
              </>
            ) : (
              field.choices.map((choice) => (
                <option key={choice} value={choice}>
                  {choice}
                </option>
              ))
            )}
          </select>
        ) : (
          <input
            id={id}
            name={`s.${field.key}`}
            type={INPUT_TYPE[field.kind] ?? "text"}
            required={field.required}
            min={field.kind === "PORT" ? 1 : field.kind === "SECONDS" ? 30 : undefined}
            max={field.kind === "PORT" ? 65535 : field.kind === "SECONDS" ? 86400 : undefined}
            defaultValue={value !== undefined ? String(value) : ""}
            placeholder={field.defaultValue !== null && field.defaultValue !== undefined ? String(field.defaultValue) : undefined}
            dir="ltr"
            autoComplete="off"
            spellCheck={false}
            aria-invalid={error ? true : undefined}
            aria-describedby={`${id}-msg`}
          />
        )}
        <span id={`${id}-msg`} className={error ? styles.error : styles.hint}>
          {error ?? help}
        </span>
      </label>
    );
  };

  const group = (g: ChannelField["group"]) => {
    const fields = spec.fields.filter((x) => x.group === g);
    if (fields.length === 0) return null;
    return (
      <fieldset className={styles.group} key={g}>
        <legend className={styles.groupTitle}>{labels.groups[g]}</legend>
        <div className={styles.fields}>{fields.map(settingField)}</div>
      </fieldset>
    );
  };

  const advanced = spec.fields.filter((x) => x.group === "ADVANCED");
  const advancedHasError = advanced.some((x) => errors[`settings.${x.key}`]);
  const stepNames = [labels.steps.protocol, labels.steps.connection, labels.steps.security];

  return (
    <form ref={form} onSubmit={submit} className={styles.wizard} noValidate>
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="_base" value={base} />
      {companyId && <input type="hidden" name="companyId" value={companyId} />}
      {channel && <input type="hidden" name="id" value={channel.id} />}

      <ol className={styles.stepper}>
        {stepNames.map((name, i) => {
          const s = i as Step;
          const state = stepErrors(s) ? "error" : s === step ? "current" : s < step || s <= reached ? "done" : "todo";
          return (
            <li key={name}>
              <button
                type="button"
                className={styles.stepButton}
                data-state={state}
                aria-current={s === step ? "step" : undefined}
                disabled={s > reached}
                onClick={() => go(s)}
              >
                <span className={styles.stepNum}>{labels.stepOf.replace("{n}", String(i + 1))}</span>
                <span className={styles.stepName}>{name}</span>
              </button>
            </li>
          );
        })}
      </ol>

      {alert && (
        <p className={styles.alert} role="alert">
          {alert}
        </p>
      )}

      {/* Step 1: protocol, direction, partner, applications. */}
      <fieldset ref={pane0} className={styles.pane} hidden={step !== 0} style={{ border: 0, padding: 0, margin: 0 }}>
        <p className={styles.paneLead}>{labels.stepLeads.protocol}</p>
        <fieldset className={styles.choices}>
          <legend className={styles.groupTitle}>{f.protocol}</legend>
          {catalog.protocols.map((p) => (
            <label key={p.code} className={styles.choice}>
              <input
                type="radio"
                name="protocol"
                value={p.code}
                checked={protocol === p.code}
                onChange={() => {
                  setProtocol(p.code);
                  setErrors({});
                  setAlert(null);
                }}
              />
              <span className={styles.glyph} aria-hidden="true">
                {p.code === "WEBHOOK" ? "HOOK" : p.code}
              </span>
              <span className={styles.choiceText}>
                <b>{p.code}</b>
                <small>{labels.protocolCards[p.code]}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <div className={styles.fields}>
          <label className={styles.field} data-invalid={errors.name ? "true" : undefined}>
            <span>
              {f.name}
              <span className={styles.req} aria-hidden="true"> *</span>
            </span>
            <input name="name" required maxLength={120} defaultValue={channel?.name} aria-invalid={errors.name ? true : undefined} />
            {errors.name && <span className={styles.error}>{errors.name}</span>}
          </label>
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
        </div>
        <fieldset className={styles.group}>
          <legend className={styles.groupTitle}>{f.direction}</legend>
          <div className={styles.segmented} role="radiogroup">
            {catalog.directions.map((d) => (
              <label key={d}>
                <input type="radio" name="direction" value={d} defaultChecked={(channel?.direction ?? "IN") === d} />
                {labels.directions[d]}
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset className={styles.group}>
          <legend className={styles.groupTitle}>{f.apps}</legend>
          <div className={styles.checks}>
            <label>
              <input type="checkbox" name="usedByMystix" defaultChecked={channel ? channel.usedByMystix : defaultApp === "MYSTIX"} />
              {f.mystix}
            </label>
            <label>
              <input type="checkbox" name="usedByAtelys" defaultChecked={channel ? channel.usedByAtelys : defaultApp === "ATELYS"} />
              {f.atelys}
            </label>
          </div>
          {errors.usedByMystix && <span className={styles.error}>{errors.usedByMystix}</span>}
        </fieldset>
      </fieldset>

      {/* Step 2: connection and files; rarely changed parameters folded. Keyed by protocol: a new protocol starts clean. */}
      <fieldset ref={pane1} className={styles.pane} hidden={step !== 1} style={{ border: 0, padding: 0, margin: 0 }} key={`c-${protocol}`}>
        <p className={styles.paneLead}>
          {labels.stepLeads.connection} <span className={styles.hint}>{labels.protocolHints[protocol]}</span>
        </p>
        {group("CONNECTION")}
        {group("FILES")}
        {advanced.length > 0 && (
          <details className={styles.advanced} open={advancedHasError || undefined}>
            <summary>{labels.groups.ADVANCED}</summary>
            <div className={styles.fields}>{advanced.map(settingField)}</div>
          </details>
        )}
      </fieldset>

      {/* Step 3: security, secret reference, status. */}
      <fieldset ref={pane2} className={styles.pane} hidden={step !== 2} style={{ border: 0, padding: 0, margin: 0 }} key={`s-${protocol}`}>
        <p className={styles.paneLead}>{labels.stepLeads.security}</p>
        {group("SECURITY")}
        <label className={styles.field} data-invalid={errors.credentialRef ? "true" : undefined}>
          <span>
            {f.credentialRef}
            {spec.needsCredential && <span className={styles.hint}> · {f.credentialNeeded}</span>}
          </span>
          <input
            name="credentialRef"
            maxLength={120}
            pattern="[A-Za-z0-9._:/\-]{1,120}"
            defaultValue={channel?.credentialRef ?? ""}
            placeholder="vault:sftp/partenaire"
            dir="ltr"
            autoComplete="off"
            spellCheck={false}
            aria-describedby="credential-help"
          />
          <span id="credential-help" className={errors.credentialRef ? styles.error : styles.hint}>
            {errors.credentialRef ?? f.credentialHelp}
          </span>
        </label>
        <fieldset className={styles.choices}>
          <legend className={styles.groupTitle}>{f.status}</legend>
          {catalog.statuses.map((s) => (
            <label key={s} className={styles.choice}>
              <input type="radio" name="status" value={s} defaultChecked={(channel?.status ?? "DRAFT") === s} />
              <span className={`${styles.dot} ${styles[`dot${s}`]}`} aria-hidden="true" />
              <span className={styles.choiceText}>
                <b>{labels.statuses[s]}</b>
                <small>{labels.statusCards[s]}</small>
              </span>
            </label>
          ))}
        </fieldset>
      </fieldset>

      <div className={styles.footer}>
        {step > 0 && (
          <button type="button" className={portal.btn} onClick={() => setStep((step - 1) as Step)}>
            {labels.previous}
          </button>
        )}
        <span className={styles.spacer} />
        <button type="submit" className={`${portal.btn} ${portal.btnPrimary}`} disabled={pending} aria-busy={pending || undefined}>
          {step < 2 ? labels.next : channel ? f.save : f.create}
        </button>
      </div>
    </form>
  );
}
