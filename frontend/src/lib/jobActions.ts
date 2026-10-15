"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { isUuid, sendForCompany, type QueueJob } from "./api";
import { hasLocale } from "./i18n";

const text = (form: FormData, key: string) => String(form.get(key) ?? "").trim();

/** Sends a DEAD job back to the queue (attempts reset), then shows the outcome on the jobs page. */
export async function replayJob(form: FormData) {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const id = text(form, "id");
  const status = text(form, "status");
  const back = (query: string) => `/${lang}/jobs?${status ? `status=${encodeURIComponent(status)}&` : ""}${query}`;
  if (!isUuid(id)) redirect(back("error=VALIDATION_FAILED"));
  const result = await sendForCompany<QueueJob>("POST", `/api/v1/queue/jobs/${id}/replay`);
  revalidatePath(`/${lang}/jobs`);
  redirect(back(result.ok ? `replayed=${id}` : `error=${encodeURIComponent(result.errorCode)}`));
}
