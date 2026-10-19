"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "@/app/[lang]/atelys/atelys.module.css";

type Item = { href: string; label: string; icon: keyof typeof atelysIcons; exact?: boolean };
type Group = { title: string; items: Item[] };

const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const;

/** Line icons of the Atelys navigation, drawn for this app (no icon library). */
const atelysIcons = {
  dashboard: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <rect x="3" y="3" width="8" height="10" rx="2" />
      <rect x="13" y="3" width="8" height="6" rx="2" />
      <rect x="13" y="11" width="8" height="10" rx="2" />
      <rect x="3" y="15" width="8" height="6" rx="2" />
    </svg>
  ),
  workOrders: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M4 20V9l5 3V9l5 3V5h6v15z" />
      <path d="M8 16h2M13 16h2" />
    </svg>
  ),
  boms: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <rect x="9" y="3" width="6" height="5" rx="1.5" />
      <rect x="3" y="16" width="6" height="5" rx="1.5" />
      <rect x="15" y="16" width="6" height="5" rx="1.5" />
      <path d="M12 8v4M6 16v-4h12v4" />
    </svg>
  ),
  stock: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z" />
      <path d="M4 7.5L12 12l8-4.5M12 12v9" />
    </svg>
  ),
  receipts: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M3 13l3-8h12l3 8v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      <path d="M3 13h5l1 2h6l1-2h5M12 4v6M9.5 7.5L12 10l2.5-2.5" />
    </svg>
  ),
  transfers: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M4 8h14M14 4l4 4-4 4M20 16H6M10 12l-4 4 4 4" />
    </svg>
  ),
  inventories: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <rect x="5" y="3" width="14" height="18" rx="2" />
      <path d="M9 3v3h6V3M9 12l2 2 4-4M9 17h6" />
    </svg>
  ),
  shipments: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M3 6h11v10H3zM14 9h4l3 3v4h-7" />
      <circle cx="7" cy="18" r="2" />
      <circle cx="17" cy="18" r="2" />
    </svg>
  ),
  traceability: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M4 4h5v5H4zM15 4h5v5h-5zM4 15h5v5H4zM15 15h2v2M19 19h1v1M17 17h3M15 20h2" />
    </svg>
  ),
  channels: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M4 7h11M12 4l3 3-3 3M20 17H9M12 14l-3 3 3 3" />
    </svg>
  ),
  devices: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <rect x="6" y="2.5" width="12" height="19" rx="2.5" />
      <path d="M9 6h6v5H9zM9.5 14.5h1M13.5 14.5h1M9.5 17.5h1M13.5 17.5h1" />
    </svg>
  ),
  settings: (
    <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0" />
      <circle cx="16" cy="6" r="2" />
      <circle cx="10" cy="12" r="2" />
      <circle cx="18" cy="18" r="2" />
    </svg>
  ),
};

export type AtelysNavGroup = Group;

export function AtelysNav({ groups, label }: { groups: Group[]; label: string }) {
  const pathname = usePathname() ?? "";
  return (
    <nav aria-label={label} style={{ display: "contents" }}>
      {groups.map((g) => (
        <div key={g.title} className={styles.navGroup}>
          <span className={styles.navTitle}>{g.title}</span>
          {g.items.map((item) => {
            const active = item.exact ? pathname === item.href : pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={styles.navLink}
                aria-current={active ? "page" : undefined}
              >
                {atelysIcons[item.icon]}
                {item.label}
              </Link>
            );
          })}
        </div>
      ))}
    </nav>
  );
}
