"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { authenticate, endSession, SESSION_COOKIE, type AuthResult } from "@/lib/api";
import { hasLocale, type Locale } from "@/lib/i18n";

/** What a sign-in form shows after a failed attempt; the typed e-mail and name are kept, never the password. */
export type AuthFormState = {
  errorCode: string | null;
  message: string | null;
  fields: string[];
  email: string;
  displayName?: string;
};

const text = (form: FormData, key: string) => String(form.get(key) ?? "");

/** Only a same-site path of the portal: no open redirect. */
function safeNext(lang: Locale, next: string) {
  return next.startsWith(`/${lang}`) && !next.startsWith("//") && !next.includes("\\") ? next : `/${lang}`;
}

async function keepSession(result: Extract<AuthResult, { ok: true }>) {
  (await cookies()).set(SESSION_COOKIE, result.token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    expires: new Date(result.expiresAt),
  });
}

function failed(
  lang: Locale,
  result: Extract<AuthResult, { ok: false }>,
  email: string,
  displayName?: string,
): AuthFormState {
  return { errorCode: result.errorCode, message: result.message?.[lang] ?? null, fields: result.fields, email, displayName };
}

export async function loginAction(_previous: AuthFormState, form: FormData): Promise<AuthFormState> {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const email = text(form, "email").trim();
  const result = await authenticate("/api/v1/auth/login", { email, password: text(form, "password") });
  if (!result.ok) return failed(lang, result, email);
  await keepSession(result);
  redirect(safeNext(lang, text(form, "next")));
}

/** First administrator of a new installation; the backend refuses it once any account exists. */
export async function setupAction(_previous: AuthFormState, form: FormData): Promise<AuthFormState> {
  const lang = text(form, "lang");
  if (!hasLocale(lang)) redirect("/");
  const email = text(form, "email").trim();
  const displayName = text(form, "displayName").trim();
  if (text(form, "password") !== text(form, "confirm")) {
    return { errorCode: "PASSWORD_MISMATCH", message: null, fields: ["confirm"], email, displayName };
  }
  const result = await authenticate("/api/v1/auth/setup", { email, displayName, password: text(form, "password") });
  if (!result.ok) return failed(lang, result, email, displayName);
  await keepSession(result);
  redirect(`/${lang}/clients`);
}

export async function logoutAction(form: FormData) {
  const lang = text(form, "lang");
  await endSession();
  (await cookies()).delete(SESSION_COOKIE);
  redirect(`/${hasLocale(lang) ? lang : "fr"}/login`);
}
