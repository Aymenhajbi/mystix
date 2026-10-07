import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound, redirect } from "next/navigation";
import { LoginForm } from "@/components/AuthForms";
import { AuthShell } from "@/components/AuthShell";
import { getMe, setupNeeded } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../dictionaries";

export async function generateMetadata({ params }: PageProps<"/[lang]/login">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).auth.loginTitle } : {};
}

export default async function LoginPage({ params, searchParams }: PageProps<"/[lang]/login">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  if (await getMe()) redirect(`/${lang}`);
  if ((await setupNeeded()) === true) redirect(`/${lang}/setup`);
  const dict = await getDictionary(lang);
  const query = await searchParams;
  const next = (Array.isArray(query.next) ? query.next[0] : query.next) ?? `/${lang}`;
  return (
    <AuthShell lang={lang} dict={dict} eyebrow={dict.auth.loginEyebrow} title={dict.auth.loginTitle} lead={dict.auth.loginLead}>
      <LoginForm lang={lang} next={next} labels={dict.auth} />
    </AuthShell>
  );
}
