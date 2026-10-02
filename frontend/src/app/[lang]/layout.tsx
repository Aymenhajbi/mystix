import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { IBM_Plex_Mono, IBM_Plex_Sans, IBM_Plex_Sans_Arabic } from "next/font/google";
import { CommandPalette, type PaletteItem } from "@/components/CommandPalette";
import { Wordmark } from "@/components/Icons";
import { LocaleSwitch } from "@/components/LocaleSwitch";
import { MobileNav, Rail } from "@/components/Rail";
import { ThemeToggle, themeInitScript } from "@/components/ThemeToggle";
import { fetchBackendHealth, getPortalCompany, listInvoices } from "@/lib/api";
import { direction, formatAmount, hasLocale, locales } from "@/lib/i18n";
import { getDictionary } from "./dictionaries";
import "../globals.css";
import styles from "./portal.module.css";

const plexSans = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-plex-sans",
  display: "swap",
});
const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-plex-mono",
  display: "swap",
});
const plexArabic = IBM_Plex_Sans_Arabic({
  subsets: ["arabic"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-plex-arabic",
  display: "swap",
});

export function generateStaticParams() {
  return locales.map((lang) => ({ lang }));
}

export async function generateMetadata({ params }: LayoutProps<"/[lang]">): Promise<Metadata> {
  const { lang } = await params;
  if (!hasLocale(lang)) return {};
  const dict = await getDictionary(lang);
  return { title: { default: dict.meta.title, template: `%s · ${dict.meta.title}` }, description: dict.meta.description };
}

export default async function RootLayout({ children, params }: LayoutProps<"/[lang]">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const [health, company, invoices] = await Promise.all([fetchBackendHealth(), getPortalCompany(), listInvoices()]);

  const navLabels = {
    main: dict.nav.main,
    home: dict.nav.home,
    cockpit: dict.nav.cockpit,
    invoices: dict.nav.invoices,
    logs: dict.nav.logs,
  };
  const paletteItems: PaletteItem[] = [
    { kind: dict.palette.kindPage, label: dict.nav.cockpit, extra: "", href: `/${lang}`, keywords: dict.nav.cockpit },
    {
      kind: dict.palette.kindPage,
      label: dict.nav.invoices,
      extra: "",
      href: `/${lang}/invoices`,
      keywords: dict.nav.invoices,
    },
    { kind: dict.palette.kindPage, label: dict.nav.logs, extra: "", href: `/${lang}/logs`, keywords: `${dict.nav.logs} log erreur error` },
    ...(invoices.kind === "ok"
      ? invoices.data.map((i) => ({
          kind: dict.palette.kindInvoice,
          label: `${i.number} · ${i.buyerName ?? dict.common.none}`,
          extra: `${formatAmount(lang, i.payableAmount, i.currency)} · ${dict.status[i.status]}`,
          href: `/${lang}/invoices/${i.id}`,
          keywords: [i.number, i.buyerName ?? "", i.clearance.reference ?? "", dict.status[i.status]].join(" "),
        }))
      : []),
  ];
  const apiLabel =
    health.status === "UP" ? dict.top.apiUp : health.status === "DOWN" ? dict.top.apiDown : dict.top.apiUnreachable;

  return (
    <html
      lang={lang}
      dir={direction(lang)}
      className={`${plexSans.variable} ${plexMono.variable} ${plexArabic.variable}`}
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInitScript }} />
      </head>
      <body>
        <a href="#main" className="skip-link">
          {dict.nav.skip}
        </a>
        <div className={styles.shell}>
          <Rail lang={lang} labels={navLabels} />
          <div className={styles.frame}>
            <header className={styles.top}>
              <Link href={`/${lang}`} className={styles.brand}>
                <Wordmark />
              </Link>
              {company?.kind === "ok" && <span className={styles.tenant}>{company.data.legalName}</span>}
              <span className={`${styles.chip} ${styles.chipSim}`} title={dict.top.simulatedTitle}>
                {dict.top.simulated}
              </span>
              <CommandPalette
                items={paletteItems}
                labels={{
                  trigger: dict.top.search,
                  triggerLabel: dict.top.searchLabel,
                  dialog: dict.palette.label,
                  placeholder: dict.palette.placeholder,
                  empty: dict.palette.empty,
                  hint: dict.palette.hint,
                }}
              />
              <div className={styles.topActions}>
                <span className={styles.live}>
                  <span
                    className={`${styles.dot} ${health.status === "UP" ? styles.dotOk : styles.dotCrit}`}
                    aria-hidden="true"
                  />
                  {apiLabel}
                </span>
                <ThemeToggle label={dict.top.theme} names={dict.top.themeNames} />
                <LocaleSwitch
                  current={lang}
                  label={dict.nav.switchTo}
                  ariaLabel={dict.nav.switchToLabel}
                  className={styles.textButton}
                />
              </div>
              <MobileNav lang={lang} labels={navLabels} />
            </header>
            <main id="main" className={styles.main} tabIndex={-1}>
              {children}
            </main>
          </div>
        </div>
      </body>
    </html>
  );
}
