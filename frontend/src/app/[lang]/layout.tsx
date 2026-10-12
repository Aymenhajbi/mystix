import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { IBM_Plex_Mono, IBM_Plex_Sans, IBM_Plex_Sans_Arabic } from "next/font/google";
import { AtelysShell } from "@/components/AtelysShell";
import { CommandPalette, type PaletteItem } from "@/components/CommandPalette";
import { Wordmark } from "@/components/Icons";
import { LocaleSwitch } from "@/components/LocaleSwitch";
import { MobileNav, Rail } from "@/components/Rail";
import { ThemeToggle, themeInitScript } from "@/components/ThemeToggle";
import { fetchBackendHealth, getMe, getPortalCompany, listInvoices } from "@/lib/api";
import { direction, formatAmount, hasLocale, locales } from "@/lib/i18n";
import { isAtelysPath, isPublicPath, PATH_HEADER } from "@/lib/routes";
import { getDictionary } from "./dictionaries";
import { logoutAction } from "./login/actions";
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

function initials(name: string) {
  const parts = name.trim().split(/s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase() || "?";
}

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
  const htmlProps = {
    lang,
    dir: direction(lang),
    className: `${plexSans.variable} ${plexMono.variable} ${plexArabic.variable}`,
    suppressHydrationWarning: true,
  };
  const head = (
    <head>
      <script dangerouslySetInnerHTML={{ __html: themeInitScript }} />
    </head>
  );

  // Sign-in pages (ADR-0013) have their own frame and must not call the session-bound APIs.
  const path = (await headers()).get(PATH_HEADER) ?? "";
  if (isPublicPath(path)) {
    return (
      <html {...htmlProps}>
        {head}
        <body>
          <a href="#main" className="skip-link">
            {dict.nav.skip}
          </a>
          {children}
        </body>
      </html>
    );
  }

  const me = await getMe();
  if (!me) redirect(`/${lang}/login?next=${encodeURIComponent(path || `/${lang}`)}`);
  const account = (
    <details className={styles.account}>
      <summary aria-label={`${dict.auth.account} : ${me.displayName}`}>
        <span className={styles.avatar} aria-hidden="true">
          {initials(me.displayName)}
        </span>
      </summary>
      <div className={styles.accountMenu}>
        <p className={styles.accountName}>{me.displayName}</p>
        <p className={styles.accountMail} dir="ltr">
          {me.email}
        </p>
        {me.operator && <span className={`${styles.chip} ${styles.chipNeutral}`}>{dict.auth.operator}</span>}
        <form action={logoutAction}>
          <input type="hidden" name="lang" value={lang} />
          <button type="submit" className={styles.logout}>
            {dict.auth.logout}
          </button>
        </form>
      </div>
    </details>
  );

  // Atelys (ADR-0014): the production and warehouse app, same session, its own frame and accent.
  if (isAtelysPath(path)) {
    return (
      <html {...htmlProps}>
        {head}
        <body className="atelys">
          <a href="#main" className="skip-link">
            {dict.nav.skip}
          </a>
          <AtelysShell
            lang={lang}
            dict={dict}
            tenant={me.activeCompany?.legalName ?? null}
            account={account}
          >
            {children}
          </AtelysShell>
        </body>
      </html>
    );
  }
  const [health, company, invoices] = await Promise.all([fetchBackendHealth(), getPortalCompany(), listInvoices()]);

  const navLabels = {
    main: dict.nav.main,
    home: dict.nav.home,
    clients: dict.nav.clients,
    cockpit: dict.nav.cockpit,
    flow: dict.nav.flow,
    mapping: dict.nav.mapping,
    invoices: dict.nav.invoices,
    stock: dict.nav.stock,
    logs: dict.nav.logs,
  };
  const paletteItems: PaletteItem[] = [
    { kind: dict.palette.kindPage, label: dict.nav.clients, extra: "", href: `/${lang}/clients`, keywords: `${dict.nav.clients} client environnement flux` },
    { kind: dict.palette.kindPage, label: dict.nav.cockpit, extra: "", href: `/${lang}`, keywords: dict.nav.cockpit },
    {
      kind: dict.palette.kindPage,
      label: dict.nav.invoices,
      extra: "",
      href: `/${lang}/invoices`,
      keywords: dict.nav.invoices,
    },
    { kind: dict.palette.kindPage, label: dict.nav.flow, extra: "", href: `/${lang}/flow`, keywords: `${dict.nav.flow} flux live direct` },
    { kind: dict.palette.kindPage, label: dict.nav.stock, extra: "", href: `/${lang}/stock`, keywords: `${dict.nav.stock} stock inventaire ATP entrepôt INVRPT DESADV RECADV` },
    { kind: dict.palette.kindPage, label: dict.nav.mapping, extra: "", href: `/${lang}/mapping`, keywords: `${dict.nav.mapping} mapping lignée lineage UBL` },
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
    <html {...htmlProps}>
      {head}
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
              <Link href={`/${lang}/clients`} className={styles.tenant} title={dict.clients.switch}>
                {company?.kind === "ok" ? company.data.legalName : dict.clients.switch}
              </Link>
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
                {account}
                <Link href={`/${lang}/atelys`} className={styles.appSwitch}>
                  <span className={styles.appSwitchDot} aria-hidden="true" />
                  {dict.atelys.switchToAtelys}
                </Link>
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
