"use server";

import { redirect } from "next/navigation";
import { isUuid, sendFlowMutation, type ExchangeFlow } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";

const text = (form: FormData, key: string) => String(form.get(key) ?? "").trim();

/** Creates a draft flow, then opens it; on failure returns to the form with the error code. */
export async function createFlowAction(form: FormData) {
  const lang = text(form, "lang");
  const companyId = text(form, "companyId");
  if (!hasLocale(lang) || !isUuid(companyId)) redirect("/");
  const base = `/${lang}/clients/${companyId}`;
  const result = await sendFlowMutation<ExchangeFlow>(companyId, "POST", "/api/v1/flows", {
    name: text(form, "name"),
    sourceChannel: text(form, "sourceChannel"),
    sourceFormat: text(form, "sourceFormat"),
    targetFormat: text(form, "targetFormat"),
    targetChannel: text(form, "targetChannel"),
  });
  if (!result.ok) redirect(`${base}/flows/new?error=${encodeURIComponent(result.errorCode)}`);
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
