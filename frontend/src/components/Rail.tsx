"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "@/app/[lang]/portal.module.css";
import { Icon, Monogram } from "./Icons";

type Labels = { main: string; home: string; clients: string; cockpit: string; flow: string; mapping: string; invoices: string; logs: string };

/** Icon rail of the Cockpit mockup. Only screens that exist are listed. */
export function Rail({ lang, labels }: { lang: string; labels: Labels }) {
  const pathname = usePathname() ?? "";
  const items = [
    {
      href: `/${lang}/clients`,
      label: labels.clients,
      icon: "clients" as const,
      active: pathname.startsWith(`/${lang}/clients`),
    },
    { href: `/${lang}`, label: labels.cockpit, icon: "cockpit" as const, active: pathname === `/${lang}` },
    {
      href: `/${lang}/flow`,
      label: labels.flow,
      icon: "flow" as const,
      active: pathname.startsWith(`/${lang}/flow`),
    },
    {
      href: `/${lang}/mapping`,
      label: labels.mapping,
      icon: "mapping" as const,
      active: pathname.startsWith(`/${lang}/mapping`),
    },
    {
      href: `/${lang}/invoices`,
      label: labels.invoices,
      icon: "invoices" as const,
      active: pathname.startsWith(`/${lang}/invoices`),
    },
    {
      href: `/${lang}/logs`,
      label: labels.logs,
      icon: "logs" as const,
      active: pathname.startsWith(`/${lang}/logs`),
    },
  ];
  return (
    <nav className={styles.rail} aria-label={labels.main}>
      <Link href={`/${lang}`} className={styles.logo} aria-label={labels.home}>
        <Monogram />
      </Link>
      {items.map((item) => (
        <Link
          key={item.href}
          href={item.href}
          className={styles.navItem}
          aria-label={item.label}
          aria-current={item.active ? "page" : undefined}
        >
          <Icon name={item.icon} />
          <span className={styles.tip} aria-hidden="true">
            {item.label}
          </span>
        </Link>
      ))}
      <div className={styles.railSpacer} />
    </nav>
  );
}

/** Same destinations for phones, where the rail is hidden. */
export function MobileNav({ lang, labels }: { lang: string; labels: Labels }) {
  const pathname = usePathname() ?? "";
  return (
    <nav className={styles.mobileNav} aria-label={labels.main}>
      <Link
        href={`/${lang}/clients`}
        className={styles.filter}
        aria-current={pathname.startsWith(`/${lang}/clients`) ? "true" : undefined}
      >
        {labels.clients}
      </Link>
      <Link
        href={`/${lang}`}
        className={styles.filter}
        aria-current={pathname === `/${lang}` ? "true" : undefined}
      >
        {labels.cockpit}
      </Link>
      <Link
        href={`/${lang}/flow`}
        className={styles.filter}
        aria-current={pathname.startsWith(`/${lang}/flow`) ? "true" : undefined}
      >
        {labels.flow}
      </Link>
      <Link
        href={`/${lang}/mapping`}
        className={styles.filter}
        aria-current={pathname.startsWith(`/${lang}/mapping`) ? "true" : undefined}
      >
        {labels.mapping}
      </Link>
      <Link
        href={`/${lang}/invoices`}
        className={styles.filter}
        aria-current={pathname.startsWith(`/${lang}/invoices`) ? "true" : undefined}
      >
        {labels.invoices}
      </Link>
      <Link
        href={`/${lang}/logs`}
        className={styles.filter}
        aria-current={pathname.startsWith(`/${lang}/logs`) ? "true" : undefined}
      >
        {labels.logs}
      </Link>
    </nav>
  );
}
