export const locales = ["fr", "ar"] as const;
export type Locale = (typeof locales)[number];
export const defaultLocale: Locale = "fr";

export const hasLocale = (value: string): value is Locale => (locales as readonly string[]).includes(value);

export const direction = (locale: Locale) => (locale === "ar" ? "rtl" : "ltr");

/** BCP 47 tag for Intl formatting. Moroccan Arabic formatting keeps Latin digits for amounts and dates. */
export const intlTag = (locale: Locale) => (locale === "ar" ? "ar-MA" : "fr-MA");

export function formatAmount(locale: Locale, amount: string | null, currency: string | null) {
  if (amount === null || currency === null) return "—";
  return new Intl.NumberFormat(intlTag(locale), {
    style: "currency",
    currency,
    numberingSystem: "latn",
  }).format(Number(amount));
}

export function formatDate(locale: Locale, isoDate: string) {
  return new Intl.DateTimeFormat(intlTag(locale), {
    dateStyle: "medium",
    numberingSystem: "latn",
    timeZone: "Africa/Casablanca",
  }).format(new Date(isoDate));
}

export function formatDateTime(locale: Locale, isoDateTime: string) {
  return new Intl.DateTimeFormat(intlTag(locale), {
    dateStyle: "medium",
    timeStyle: "short",
    numberingSystem: "latn",
    timeZone: "Africa/Casablanca",
  }).format(new Date(isoDateTime));
}
