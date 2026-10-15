"use client";

import { useActionState } from "react";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import portal from "@/app/[lang]/portal.module.css";
import { rotateKeyAction, type ListenerActionResult } from "@/lib/connectivityActions";
import { OneTimeKey } from "./OneTimeKey";

/** New API key on demand; shown once in this component's state, never written to the URL or storage. */
export function KeyRotator({ lang, id, dict }: { lang: string; id: string; dict: Dictionary }) {
  const c = dict.connectivity;
  const [state, action, pending] = useActionState<ListenerActionResult | null, FormData>(rotateKeyAction, null);
  return (
    <form
      action={action}
      onSubmit={(e) => {
        if (!window.confirm(c.detail.rotateConfirm)) e.preventDefault();
      }}
    >
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="id" value={id} />
      <button type="submit" className={`${portal.btn} ${portal.btnSm}`} disabled={pending}>
        {c.detail.rotate}
      </button>
      {state?.ok && state.apiKey && (
        <OneTimeKey title={c.detail.rotated} lead={c.form.keyLead} value={state.apiKey} copyLabel={c.form.copy} copiedLabel={c.form.copied} />
      )}
      {state && !state.ok && (
        <p className={portal.message} role="alert">
          {c.errors[state.errorCode as keyof typeof c.errors] ?? c.errors.default}
        </p>
      )}
    </form>
  );
}
