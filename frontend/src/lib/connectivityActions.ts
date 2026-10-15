"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { isUuid, sendForCompany, type FieldError } from "./api";
import type { EntryPoint, ListenerView } from "./connectivity";
import { hasLocale } from "./i18n";

const text = (form: FormData, key: string) => String(form.get(key) ?? "").trim();

/** What a form gets back: the API key once on success, or the refusal with its field errors. */
export type ListenerActionResult =
  | { ok: true; id: string; apiKey: string | null }
  | { ok: false; errorCode: string; fieldErrors: FieldError[] };

function lang(form: FormData) {
  const value = text(form, "lang");
  if (!hasLocale(value)) redirect("/");
  return value;
}

export async function createEntryPointAction(form: FormData) {
  const l = lang(form);
  const result = await sendForCompany<EntryPoint>("POST", "/api/v1/connectivity/entry-points", {
    code: text(form, "code").toUpperCase(),
    name: text(form, "name"),
    flowId: text(form, "flowId") || null,
    documentType: "INVOICE",
    inputFormat: text(form, "inputFormat") || "JSON",
    enabled: true,
  });
  if (!result.ok) {
    const fields = (result.fieldErrors ?? []).map((f) => f.field).join(",");
    redirect(`/${l}/connectivity/entry-points/new?error=${encodeURIComponent(result.errorCode)}${fields ? `&fields=${encodeURIComponent(fields)}` : ""}`);
  }
  revalidatePath(`/${l}/connectivity`);
  redirect(`/${l}/connectivity?entryPoint=1`);
}

/** Creates a listener; the API key comes back once, for the form to show. Nothing is started. */
export async function createListenerAction(_: ListenerActionResult | null, form: FormData): Promise<ListenerActionResult> {
  const l = lang(form);
  const maxKb = Number(text(form, "maxKb"));
  const result = await sendForCompany<ListenerView>("POST", "/api/v1/connectivity/listeners", {
    name: text(form, "name"),
    kind: text(form, "kind") === "SFTP_POLLING" ? "SFTP_POLLING" : "HTTP_INBOUND",
    channelId: text(form, "channelId") || null,
    entryPointId: text(form, "entryPointId") || null,
    settings: Number.isFinite(maxKb) && maxKb > 0 ? { maxBytes: Math.round(maxKb * 1024) } : {},
  });
  if (!result.ok) return { ok: false, errorCode: result.errorCode, fieldErrors: result.fieldErrors ?? [] };
  revalidatePath(`/${l}/connectivity`);
  return { ok: true, id: result.data.id, apiKey: result.data.apiKey };
}

export async function rotateKeyAction(_: ListenerActionResult | null, form: FormData): Promise<ListenerActionResult> {
  const l = lang(form);
  const id = text(form, "id");
  if (!isUuid(id)) return { ok: false, errorCode: "VALIDATION_FAILED", fieldErrors: [] };
  const result = await sendForCompany<ListenerView>("POST", `/api/v1/connectivity/listeners/${id}/api-key`);
  if (!result.ok) return { ok: false, errorCode: result.errorCode, fieldErrors: result.fieldErrors ?? [] };
  revalidatePath(`/${l}/connectivity/listeners/${id}`);
  return { ok: true, id, apiKey: result.data.apiKey };
}

/** Start or stop, then back to the page it came from with the outcome; a refusal carries its reasons. */
export async function listenerLifecycleAction(form: FormData) {
  const l = lang(form);
  const id = text(form, "id");
  const action = text(form, "action") === "stop" ? "stop" : "start";
  const back = text(form, "back") === "list" ? `/${l}/connectivity` : `/${l}/connectivity/listeners/${id}`;
  const join = (q: string) => `${back}${back.includes("?") ? "&" : "?"}${q}`;
  if (!isUuid(id)) redirect(join("error=VALIDATION_FAILED"));
  const result = await sendForCompany<ListenerView>("POST", `/api/v1/connectivity/listeners/${id}/${action}`);
  revalidatePath(`/${l}/connectivity`);
  if (!result.ok) {
    const fields = (result.fieldErrors ?? []).map((f) => f.field).join(",");
    redirect(join(`error=${encodeURIComponent(result.errorCode)}${fields ? `&why=${encodeURIComponent(fields)}` : ""}`));
  }
  redirect(join(action === "start" ? "started=1" : "stopped=1"));
}

/** One acquisition cycle now on a polling listener; the counts come back in the URL (no content, no secret). */
export async function pollListenerAction(form: FormData) {
  const l = lang(form);
  const id = text(form, "id");
  const back = `/${l}/connectivity/listeners/${id}`;
  if (!isUuid(id)) redirect(`/${l}/connectivity?error=VALIDATION_FAILED`);
  const result = await sendForCompany<{ accepted: number; rejected: number; refused: number; duplicates: number; skipped: number }>(
    "POST",
    `/api/v1/connectivity/listeners/${id}/poll`,
  );
  revalidatePath(back);
  if (!result.ok) redirect(`${back}?error=${encodeURIComponent(result.errorCode)}`);
  const r = result.data;
  redirect(`${back}?polled=${[r.accepted, r.rejected, r.refused, r.duplicates, r.skipped].join("-")}`);
}
