"use client";

import { useActionState, useState } from "react";
import { useFormStatus } from "react-dom";
import styles from "@/app/[lang]/auth.module.css";
import { loginAction, setupAction, type AuthFormState } from "@/app/[lang]/login/actions";
import type { Dictionary } from "@/app/[lang]/dictionaries";

type Labels = Dictionary["auth"];
const initial: AuthFormState = { errorCode: null, message: null, fields: [], email: "" };

function Eye({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
      <circle cx="12" cy="12" r="3" />
      {!open && <path d="M4 20 20 4" />}
    </svg>
  );
}

function Submit({ label, pending }: { label: string; pending: string }) {
  const { pending: busy } = useFormStatus();
  return (
    <button type="submit" className={styles.submit} disabled={busy} aria-busy={busy}>
      {busy && <span className={styles.spinner} aria-hidden="true" />}
      {busy ? pending : label}
    </button>
  );
}

/** Password input with a show/hide button and a Caps Lock warning. */
function Password({
  id,
  name,
  label,
  labels,
  autoComplete,
  invalid,
  describedBy,
  onValue,
}: {
  id: string;
  name: string;
  label: string;
  labels: Labels;
  autoComplete: string;
  invalid: boolean;
  describedBy?: string;
  onValue?: (value: string) => void;
}) {
  const [shown, setShown] = useState(false);
  const [caps, setCaps] = useState(false);
  const capsId = `${id}-caps`;
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      <div className={styles.input}>
        <input
          id={id}
          name={name}
          type={shown ? "text" : "password"}
          autoComplete={autoComplete}
          required
          maxLength={200}
          className={styles.withToggle}
          aria-invalid={invalid || undefined}
          aria-describedby={[describedBy, caps ? capsId : null].filter(Boolean).join(" ") || undefined}
          onKeyUp={(e) => setCaps(e.getModifierState("CapsLock"))}
          onChange={(e) => onValue?.(e.target.value)}
          dir="ltr"
        />
        <button
          type="button"
          className={styles.toggle}
          aria-label={shown ? labels.hide : labels.show}
          aria-pressed={shown}
          onClick={() => setShown((v) => !v)}
        >
          <Eye open={!shown} />
        </button>
      </div>
      {caps && (
        <p id={capsId} className={styles.caps} role="status">
          {labels.capsLock}
        </p>
      )}
    </div>
  );
}

function ErrorAlert({ state, labels }: { state: AuthFormState; labels: Labels }) {
  if (!state.errorCode) return null;
  const known = labels.errors[state.errorCode as keyof Labels["errors"]];
  const message = known ?? state.message ?? labels.errors.default.replace("{code}", state.errorCode);
  return (
    <div className={styles.alert} role="alert">
      {message}
    </div>
  );
}

export function LoginForm({ lang, next, labels }: { lang: string; next: string; labels: Labels }) {
  const [state, action] = useActionState(loginAction, initial);
  const failed = state.errorCode === "AUTH_INVALID_CREDENTIALS";
  return (
    <form action={action} className={styles.form} noValidate={false}>
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="next" value={next} />
      <ErrorAlert state={state} labels={labels} />
      <div className={styles.field}>
        <label htmlFor="login-email">{labels.email}</label>
        <div className={styles.input}>
          <input
            id="login-email"
            name="email"
            type="email"
            inputMode="email"
            autoComplete="username"
            required
            maxLength={254}
            defaultValue={state.email}
            aria-invalid={failed || undefined}
            dir="ltr"
            autoFocus
          />
        </div>
      </div>
      <Password
        id="login-password"
        name="password"
        label={labels.password}
        labels={labels}
        autoComplete="current-password"
        invalid={failed}
      />
      <Submit label={labels.submit} pending={labels.submitting} />
      <p className={styles.hint}>{labels.forgot}</p>
    </form>
  );
}

/** 0..4, for the strength bar; the backend only enforces the minimum length. */
function strength(password: string) {
  if (password.length < 12) return password.length === 0 ? 0 : 1;
  let score = 2;
  if (password.length >= 16) score++;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password) && /\d/.test(password) && /[^A-Za-z0-9]/.test(password)) score++;
  return Math.min(score, 4);
}

export function SetupForm({ lang, labels }: { lang: string; labels: Labels }) {
  const [state, action] = useActionState(setupAction, initial);
  // React resets the form after each submission: the bar follows the password typed since the last result.
  const [typed, setTyped] = useState({ value: "", after: state });
  const score = typed.after === state ? strength(typed.value) : 0;
  const invalid = (field: string) => state.fields.includes(field) || undefined;
  return (
    <form action={action} className={styles.form}>
      <input type="hidden" name="lang" value={lang} />
      <ErrorAlert state={state} labels={labels} />
      <div className={styles.field}>
        <label htmlFor="setup-name">{labels.displayName}</label>
        <div className={styles.input}>
          <input
            id="setup-name"
            name="displayName"
            autoComplete="name"
            required
            maxLength={120}
            defaultValue={state.displayName}
            aria-invalid={invalid("displayName")}
            autoFocus
          />
        </div>
      </div>
      <div className={styles.field}>
        <label htmlFor="setup-email">{labels.email}</label>
        <div className={styles.input}>
          <input
            id="setup-email"
            name="email"
            type="email"
            inputMode="email"
            autoComplete="username"
            required
            maxLength={254}
            defaultValue={state.email}
            aria-invalid={invalid("email")}
            dir="ltr"
          />
        </div>
      </div>
      <Password
        id="setup-password"
        name="password"
        label={labels.password}
        labels={labels}
        autoComplete="new-password"
        invalid={!!invalid("password")}
        describedBy="setup-password-hint"
        onValue={(value) => setTyped({ value, after: state })}
      />
      <div className={styles.strength} id="setup-password-hint">
        <span className={styles.strengthBar} aria-hidden="true">
          {[1, 2, 3, 4].map((n) => (
            <span key={n} className={n <= score ? styles.on : undefined} />
          ))}
        </span>
        <span>{labels.strength[score as 0 | 1 | 2 | 3 | 4]}</span>
      </div>
      <Password
        id="setup-confirm"
        name="confirm"
        label={labels.confirm}
        labels={labels}
        autoComplete="new-password"
        invalid={!!invalid("confirm")}
      />
      <Submit label={labels.setupSubmit} pending={labels.submitting} />
    </form>
  );
}
