"use client";

import { useSyncExternalStore } from "react";
import styles from "@/app/[lang]/portal.module.css";
import { Icon } from "./Icons";

type Theme = "system" | "light" | "dark";
const KEY = "mystix-theme";
const order: Theme[] = ["system", "light", "dark"];

/** Runs before paint (inlined in <head>) so the stored theme never flashes. */
export const themeInitScript = `try{var t=localStorage.getItem("${KEY}");if(t==="light"||t==="dark")document.documentElement.setAttribute("data-theme",t)}catch(e){}`;

const listeners = new Set<() => void>();

function read(): Theme {
  const t = document.documentElement.getAttribute("data-theme");
  return t === "light" || t === "dark" ? t : "system";
}

function apply(theme: Theme) {
  if (theme === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", theme);
  try {
    if (theme === "system") localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, theme);
  } catch {
    // Storage can be unavailable (private mode): the theme still applies for this page.
  }
  listeners.forEach((l) => l());
}

export function ThemeToggle({ label, names }: { label: string; names: Record<Theme, string> }) {
  const theme = useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      return () => listeners.delete(onChange);
    },
    read,
    () => "system" as Theme,
  );
  const next = order[(order.indexOf(theme) + 1) % order.length];
  return (
    <button
      type="button"
      className={styles.iconButton}
      onClick={() => apply(next)}
      aria-label={`${label} (${names[theme]})`}
      title={names[theme]}
    >
      <Icon name="theme" />
    </button>
  );
}
