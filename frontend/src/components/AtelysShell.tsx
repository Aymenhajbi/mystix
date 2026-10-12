import Link from "next/link";
import type { ReactNode } from "react";
import styles from "@/app/[lang]/atelys/atelys.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { Locale } from "@/lib/i18n";
import { AtelysNav, type AtelysNavGroup } from "./AtelysNav";
import { MystixWordmark } from "./Icons";
import { LocaleSwitch } from "./LocaleSwitch";
import { ThemeToggle } from "./ThemeToggle";

/**
 * Frame of Atelys (ADR-0014): production and warehouses, powered by Mystix. Same session and account as the Mystix
 * portal; the switch in the top bar goes back to the EDI side.
 */
export function AtelysShell({
  lang,
  dict,
  tenant,
  account,
  children,
}: {
  lang: Locale;
  dict: Dictionary;
  tenant: string | null;
  account: ReactNode;
  children: ReactNode;
}) {
  const a = dict.atelys;
  const base = `/${lang}/atelys`;
  const groups: AtelysNavGroup[] = [
    {
      title: a.nav.production,
      items: [
        { href: base, label: a.nav.dashboard, icon: "dashboard", exact: true },
        { href: `${base}/work-orders`, label: a.nav.workOrders, icon: "workOrders" },
        { href: `${base}/boms`, label: a.nav.boms, icon: "boms" },
      ],
    },
    {
      title: a.nav.warehouse,
      items: [
        { href: `${base}/stock`, label: a.nav.stock, icon: "stock" },
        { href: `${base}/receipts`, label: a.nav.receipts, icon: "receipts" },
        { href: `${base}/transfers`, label: a.nav.transfers, icon: "transfers" },
        { href: `${base}/inventories`, label: a.nav.inventories, icon: "inventories" },
        { href: `${base}/shipments`, label: a.nav.shipments, icon: "shipments" },
      ],
    },
    {
      title: a.nav.admin,
      items: [
        { href: `${base}/traceability`, label: a.nav.traceability, icon: "traceability" },
        { href: `${base}/settings`, label: a.nav.settings, icon: "settings" },
      ],
    },
  ];
  return (
    <div className={styles.shell}>
      <aside className={styles.side}>
        <Link href={base} className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            A
          </span>
          <span className={styles.brandText}>
            <span className={styles.brandName}>{a.brand}</span>
            <span className={styles.brandTag}>{a.tagline}</span>
          </span>
        </Link>
        <AtelysNav groups={groups} label={a.nav.main} />
        <div className={styles.powered}>
          <span>{a.powered}</span>
          <MystixWordmark />
        </div>
      </aside>
      <div className={styles.frame}>
        <header className={styles.top}>
          {tenant && <span className={styles.tenant}>{tenant}</span>}
          <span className={styles.spacer} />
          <Link href={`/${lang}`} className={styles.switch} aria-label={`${a.switchLabel} : ${a.switchToMystix}`}>
            <span className={styles.switchDot} aria-hidden="true" />
            {a.switchToMystix}
          </Link>
          <ThemeToggle label={dict.top.theme} names={dict.top.themeNames} />
          <LocaleSwitch current={lang} label={dict.nav.switchTo} ariaLabel={dict.nav.switchToLabel} className={styles.switch} />
          {account}
        </header>
        <main id="main" className={styles.main} tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  );
}
