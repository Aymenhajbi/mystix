"use server";

import { redirect } from "next/navigation";
import { isUuid, sendFlowMutation, type CompanySettings, type ExchangeFlow, type Partner } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";

const text = (form: FormData, key: string) => String(form.get(key) ?? "").trim();

/** Creates a draft flow, then opens it; on failure returns to the form with the error code. */
export async function createFlowAction(form: FormData) {
  const lang = text(form, "lang");
  const companyId = text(form, "companyId");
  if (!hasLocale(lang) || !isUuid(companyId)) redirect("/");
  const base = `/${lang}/clients/${companyId}`;
  const direction = text(form, "direction") === "IN" ? "IN" : "OUT";
  const partnerId = text(form, "partnerId");
  const result = await sendFlowMutation<ExchangeFlow>(companyId, "POST", "/api/v1/flows", {
    name: text(form, "name"),
    direction,
    partnerId: isUuid(partnerId) ? partnerId : null,
    sourceChannel: text(form, "sourceChannel"),
    sourceFormat: text(form, "sourceFormat"),
    targetFormat: text(form, "targetFormat"),
    targetChannel: text(form, "targetChannel"),
  });
  if (!result.ok) redirect(`${base}/flows/new?direction=${direction}&error=${encodeURIComponent(result.errorCode)}`);
  redirect(`${base}/flows/${result.data.id}?created=1`);
}

/** Renames a flow or changes its status. */
export async function updateFlowAction(form: FormData) {
  const lang = text(form, "lang");
  const companyId = text(form, "companyId");
  const flowId = text(form, "flowId");
  if (!hasLocale(lang) || !isUuid(companyId) || !isUuid(flowId)) redirect("/");
  const page = `/${lang}/clients/${companyId}/flows/${flowId}`;
  const result = await sendFlowMutation<ExchangeFlow>(companyId, "PATCH", `/api/v1/flows/${flowId}`, {
    name: text(form, "name") || null,
    status: text(form, "status") || null,
  });
  redirect(result.ok ? `${page}?saved=1` : `${page}?error=${encodeURIComponent(result.errorCode)}`);
}

/** Adds a partner to the client environment. */
export async function createPartnerAction(form: FormData) {
  const lang = text(form, "lang");
  const companyId = text(form, "companyId");
  if (!hasLocale(lang) || !isUuid(companyId)) redirect("/");
  const base = `/${lang}/clients/${companyId}`;
  const result = await sendFlowMutation<Partner>(companyId, "POST", "/api/v1/partners", {
    name: text(form, "name"),
    type: text(form, "type"),
    ice: text(form, "ice") || null,
    gln: text(form, "gln") || null,
    reference: text(form, "reference") || null,
  });
  redirect(result.ok ? `${base}?partner=1#partners` : `${base}?error=${encodeURIComponent(result.errorCode)}#partners`);
}

/** Saves the environment settings (configurator). An unchecked box is absent from the form: rule off. */
export async function updateSettingsAction(form: FormData) {
  const lang = text(form, "lang");
  const companyId = text(form, "companyId");
  if (!hasLocale(lang) || !isUuid(companyId)) redirect("/");
  const base = `/${lang}/clients/${companyId}`;
  const result = await sendFlowMutation<CompanySettings>(companyId, "PUT", `/api/v1/companies/${companyId}/settings`, {
    enforceSellerIce: form.get("enforceSellerIce") === "on",
  });
  redirect(result.ok ? `${base}?settings=1#settings` : `${base}?error=${encodeURIComponent(result.errorCode)}#settings`);
}
