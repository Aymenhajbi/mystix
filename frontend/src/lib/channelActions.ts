"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { isUuid, portalCompanyId, sendFlowMutation, type FieldError } from "./api";
import type { Channel, ChannelCertificate } from "./channels";
import { hasLocale } from "./i18n";

const text = (form: FormData, key: string) => String(form.get(key) ?? "").trim();

/** Only pages of this portal: Mystix client environments or Atelys. */
function safeBase(lang: string, value: string) {
  return value.startsWith(`/${lang}/`) && !value.includes("//") ? value : `/${lang}`;
}

/** The environment: the one chosen in Mystix (hidden field), or the active one of the session (Atelys). */
async function company(form: FormData) {
  const chosen = text(form, "companyId");
  return isUuid(chosen) ? chosen : await portalCompanyId();
}

const join = (base: string, query: string) => `${base}${base.includes("?") ? "&" : "?"}${query}`;

/** What the wizard gets back when the backend refuses: it keeps the input and shows each error at its field. */
export type ChannelSaveResult = { errorCode: string; fieldErrors: FieldError[] };

/**
 * Creates or updates a channel, then opens its sheet. `s.<key>` fields are the protocol settings: empty ones are left
 * out (the backend applies its defaults), "true" / "false" become booleans. The backend checks everything again.
 * Called from the wizard with the form data; returns the errors instead of navigating, so nothing typed is lost.
 */
export async function saveChannel(form: FormData): Promise<ChannelSaveResult> {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const base = safeBase(lang, text(form, "_base"));
  const companyId = await company(form);
  if (!companyId) return { errorCode: "NO_COMPANY", fieldErrors: [] };
  const id = text(form, "id");
  const settings: Record<string, string | boolean> = {};
  for (const [key, raw] of form.entries()) {
    if (!key.startsWith("s.") || typeof raw !== "string") continue;
    const value = raw.trim();
    if (!value) continue;
    settings[key.slice(2)] = value === "true" ? true : value === "false" ? false : value;
  }
  const partnerId = text(form, "partnerId");
  const body = {
    name: text(form, "name"),
    protocol: text(form, "protocol"),
    direction: text(form, "direction"),
    partnerId: isUuid(partnerId) ? partnerId : null,
    usedByMystix: form.get("usedByMystix") === "on",
    usedByAtelys: form.get("usedByAtelys") === "on",
    status: text(form, "status") || "DRAFT",
    credentialRef: text(form, "credentialRef") || null,
    settings,
  };
  const result = isUuid(id)
    ? await sendFlowMutation<Channel>(companyId, "PUT", `/api/v1/channels/${id}`, body)
    : await sendFlowMutation<Channel>(companyId, "POST", "/api/v1/channels", body);
  if (!result.ok) return { errorCode: result.errorCode, fieldErrors: result.fieldErrors ?? [] };
  revalidatePath(base);
  redirect(join(`${base}/${result.data.id}`, isUuid(id) ? "saved=1" : "created=1"));
}

export async function deleteChannelAction(form: FormData) {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const base = safeBase(lang, text(form, "_base"));
  const back = safeBase(lang, text(form, "_back"));
  const companyId = await company(form);
  const id = text(form, "id");
  if (!companyId || !isUuid(id)) redirect(join(back, "error=VALIDATION_FAILED"));
  const result = await sendFlowMutation<null>(companyId, "DELETE", `/api/v1/channels/${id}`, null);
  if (!result.ok) redirect(join(back, `error=${encodeURIComponent(result.errorCode)}`));
  revalidatePath(base);
  redirect(join(base, "deleted=1"));
}

/**
 * Adds a public certificate to a channel, from a pasted PEM or an uploaded file (PEM or DER). A file that carries a
 * private key is refused by the backend and never stored.
 */
export async function addCertificateAction(form: FormData) {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const back = safeBase(lang, text(form, "_back"));
  const companyId = await company(form);
  const id = text(form, "id");
  if (!companyId || !isUuid(id)) redirect(join(back, "error=VALIDATION_FAILED"));
  let certificate = text(form, "pem");
  const file = form.get("file");
  if (!certificate && file instanceof File && file.size > 0 && file.size <= 64 * 1024) {
    const bytes = Buffer.from(await file.arrayBuffer());
    const asText = bytes.toString("latin1");
    // PEM stays text; DER (binary) travels as base64.
    certificate = asText.includes("-----BEGIN") ? asText : bytes.toString("base64");
  }
  const result = await sendFlowMutation<ChannelCertificate>(companyId, "POST", `/api/v1/channels/${id}/certificates`, {
    role: text(form, "role") === "OWN" ? "OWN" : "PARTNER",
    certificate,
  });
  redirect(join(back, result.ok ? "certificate=1#certificates" : `error=${encodeURIComponent(result.errorCode)}#certificates`));
}

export async function deleteCertificateAction(form: FormData) {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const back = safeBase(lang, text(form, "_back"));
  const companyId = await company(form);
  const id = text(form, "id");
  const certificateId = text(form, "certificateId");
  if (!companyId || !isUuid(id) || !isUuid(certificateId)) redirect(join(back, "error=VALIDATION_FAILED"));
  const result = await sendFlowMutation<null>(companyId, "DELETE", `/api/v1/channels/${id}/certificates/${certificateId}`, null);
  redirect(join(back, result.ok ? "certificateDeleted=1#certificates" : `error=${encodeURIComponent(result.errorCode)}#certificates`));
}
