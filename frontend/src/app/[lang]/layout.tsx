import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { LocaleSwitch } from "@/components/LocaleSwitch";
import { direction, hasLocale, locales } from "@/lib/i18n";
import { getDictionary } from "./dictionaries";
import "../globals.css";
import styles from "./portal.module.css";

export function generateStaticParams() {
  return locales.map((lang) => ({ lang }));
}

export async function generateMetadata({ params }: LayoutProps<"/[lang]">): Promise<Metadata> {
  const { lang } = await params;
  if (!hasLocale(lang)) return {};
  const dict = await getDictionary(lang);
  return { title: dict.meta.title, description: dict.meta.description };
}

export default async function RootLayout({ children, params }: LayoutProps<"/[lang]">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  const dict = await getDictionary(lang);

  return (
    <html lang={lang} dir={direction(lang)}>
      <body>
        <header className={styles.header}>
          <Link href={`/${lang}`} className={styles.brand}>
            Mystix
          </Link>
          <nav aria-label={dict.nav.main} className={styles.nav}>
            <Link href={`/${lang}`}>{dict.nav.home}</Link>
            <Link href={`/${lang}/invoices`}>{dict.nav.invoices}</Link>
          </nav>
          <LocaleSwitch current={lang} label={dict.nav.switchTo} ariaLabel={dict.nav.switchToLabel} />
        </header>
        <p className={styles.notice} role="note">
          {dict.common.simulatedBanner}
        </p>
        {children}
      </body>
    </html>
  );
}
