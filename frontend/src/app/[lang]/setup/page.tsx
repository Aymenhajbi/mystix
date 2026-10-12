import type { Metadata } from "next";
import { connection } from "next/server";
import { notFound, redirect } from "next/navigation";
import { SetupForm } from "@/components/AuthForms";
import { AuthShell } from "@/components/AuthShell";
import { setupNeeded } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../dictionaries";

export async function generateMetadata({ params }: PageProps<"/[lang]/setup">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: (await getDictionary(lang)).auth.setupTitle } : {};
}

/** First administrator of a new installation; closed as soon as an account exists. */
export default async function SetupPage({ params }: PageProps<"/[lang]/setup">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  if ((await setupNeeded()) !== true) redirect(`/${lang}/login`);
  const dict = await getDictionary(lang);
  return (
    <AuthShell lang={lang} dict={dict} eyebrow={dict.auth.setupEyebrow} title={dict.auth.setupTitle} lead={dict.auth.setupLead}>
      <SetupForm lang={lang} labels={dict.auth} />
    </AuthShell>
  );
}
