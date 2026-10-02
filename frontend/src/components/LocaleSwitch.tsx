"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import type { Locale } from "@/lib/i18n";

/** Links to the same page, with the same filters, in the other language. */
export function LocaleSwitch({
  current,
  label,
  ariaLabel,
  className,
}: {
  current: Locale;
  label: string;
  ariaLabel: string;
  className?: string;
}) {
  const pathname = usePathname() ?? `/${current}`;
  const search = useSearchParams()?.toString();
  const target: Locale = current === "fr" ? "ar" : "fr";
  const path = pathname.replace(new RegExp(`^/${current}(?=/|$)`), `/${target}`);
  return (
    <Link
      href={search ? `${path}?${search}` : path}
      hrefLang={target}
      lang={target}
      aria-label={ariaLabel}
      className={className}
    >
      {label}
    </Link>
  );
}
