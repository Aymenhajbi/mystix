"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { isUuid, portalCompanyId, sendFlowMutation } from "./api";
import type { Channel } from "./channels";
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

/**
 * Creates or updates a channel. `s.<key>` fields are the protocol settings: empty ones are left out (the backend
 * applies its defaults), "true" / "false" become booleans. The backend checks everything again.
 */
export async function saveChannelAction(form: FormData) {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const base = safeBase(lang, text(form, "_base"));
  const back = safeBase(lang, text(form, "_back"));
  const companyId = await company(form);
  if (!companyId) redirect(join(back, "error=NO_COMPANY"));
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
  if (!result.ok) redirect(join(back, `error=${encodeURIComponent(result.errorCode)}`));
  revalidatePath(base);
  redirect(join(base, isUuid(id) ? "saved=1" : "created=1"));
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
