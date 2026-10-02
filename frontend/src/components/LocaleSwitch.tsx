"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { Locale } from "@/lib/i18n";

/** Links to the same page in the other language. */
export function LocaleSwitch({ current, label, ariaLabel }: { current: Locale; label: string; ariaLabel: string }) {
  const pathname = usePathname() ?? `/${current}`;
  const target: Locale = current === "fr" ? "ar" : "fr";
  const href = pathname.replace(new RegExp(`^/${current}(?=/|$)`), `/${target}`);
  return (
    <Link href={href} hrefLang={target} lang={target} aria-label={ariaLabel}>
      {label}
    </Link>
  );
}
