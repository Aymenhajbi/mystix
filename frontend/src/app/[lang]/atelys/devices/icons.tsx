/** Line icons of the Device Hub pages, drawn for Atelys like the navigation icons (no icon library). */

const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const;

const paths = {
  terminal: (
    <>
      <rect x="6" y="2.5" width="12" height="19" rx="2.5" />
      <path d="M9 6h6v5H9zM9.5 14.5h1M13.5 14.5h1M9.5 17.5h1M13.5 17.5h1" />
    </>
  ),
  fleet: (
    <>
      <rect x="2.5" y="5" width="8" height="14" rx="2" />
      <rect x="13.5" y="5" width="8" height="14" rx="2" />
      <path d="M5 8.5h3M16 8.5h3" />
    </>
  ),
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  alert: (
    <>
      <path d="M12 3.5l9 16H3z" />
      <path d="M12 10v4M12 17h0" />
    </>
  ),
  error: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M9 9l6 6M15 9l-6 6" />
    </>
  ),
  offline: (
    <>
      <path d="M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0M12 19h0" />
      <path d="M3 3l18 18" />
    </>
  ),
  download: <path d="M12 3v12M7 10l5 5 5-5M4 20h16" />,
  radar: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <path d="M12 12l6-6" />
    </>
  ),
  scan: (
    <>
      <path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3" />
      <path d="M8 9v6M11 9v6M14 9v6M17 9v6" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6z" />
      <path d="M9 12l2 2 4-4" />
    </>
  ),
  keyboard: (
    <>
      <rect x="2.5" y="6" width="19" height="12" rx="2" />
      <path d="M6 10h0M9 10h0M12 10h0M15 10h0M18 10h0M7 14h10" />
    </>
  ),
  broadcast: (
    <>
      <circle cx="12" cy="12" r="2" />
      <path d="M8 8a5.5 5.5 0 0 0 0 8M16 8a5.5 5.5 0 0 1 0 8M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8h0" />
    </>
  ),
  refresh: <path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  tag: (
    <>
      <path d="M3 12V4h8l10 10-8 8z" />
      <circle cx="7.5" cy="8.5" r="1.5" />
    </>
  ),
  chevron: <path d="M6 9l6 6 6-6" />,
  profile: (
    <>
      <rect x="4" y="3" width="16" height="18" rx="2" />
      <path d="M8 8h8M8 12h8M8 16h5" />
    </>
  ),
};

export type DeviceIconName = keyof typeof paths;

export function DeviceIcon({ name, className }: { name: DeviceIconName; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={className} {...stroke}>
      {paths[name]}
    </svg>
  );
}

export const healthIcon = { OK: "check", ACTION_REQUIRED: "alert", ERROR: "error", OFFLINE: "offline" } as const;

export const adapterIcon = (id: string | null): DeviceIconName =>
  id === "honeywell-data-intent" ? "broadcast" : id === "keyboard-wedge" ? "keyboard" : "scan";
