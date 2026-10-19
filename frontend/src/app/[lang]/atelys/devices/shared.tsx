import type { Dictionary } from "@/app/[lang]/dictionaries";
import { intlTag, type Locale } from "@/lib/i18n";
import type { DeviceHealth } from "@/lib/devices";
import s from "../atelys.module.css";

/** Time of the request, for relative dates (server components render once per request). */
export function requestTime() {
  return Date.now();
}

/** "il y a 5 min" from an ISO date; falls back to nothing when the date is missing. */
export function ago(lang: Locale, iso: string | null | undefined, now: number) {
  if (!iso) return "—";
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const rtf = new Intl.RelativeTimeFormat(intlTag(lang), { numeric: "auto" });
  const abs = Math.abs(seconds);
  if (abs < 60) return rtf.format(seconds, "second");
  if (abs < 3600) return rtf.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return rtf.format(Math.round(seconds / 3600), "hour");
  return rtf.format(Math.round(seconds / 86400), "day");
}

const tone: Record<DeviceHealth, string> = {
  OK: "st-ok",
  ACTION_REQUIRED: "st-warn",
  ERROR: "st-crit",
  OFFLINE: "st-muted",
};

export function HealthBadge({ health, dict }: { health: DeviceHealth; dict: Dictionary }) {
  return (
    <span className={`${s.badge} ${s[tone[health]]}`} title={dict.atelys.devices.healthHint[health]}>
      {dict.atelys.devices.health[health]}
    </span>
  );
}

export function adapterName(dict: Dictionary, id: string | null | undefined) {
  if (!id) return dict.atelys.devices.fields.none;
  const known = (dict.atelys.devices.adapters as Record<string, { name: string }>)[id];
  return known?.name ?? id;
}

export function reasonText(dict: Dictionary, reason: string) {
  const [code, ...detail] = reason.split(":");
  const text = (dict.atelys.devices.reasons as Record<string, string>)[code.trim()];
  return text ? (detail.length ? `${text} (${detail.join(":").trim()})` : text) : reason;
}
