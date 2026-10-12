"use client";

import Link from "next/link";
import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { AtelysMark } from "./AtelysMark";
import { Monogram } from "./Icons";
import styles from "./AppSwitch.module.css";

type App = "mystix" | "atelys";

const KEY = "mystix:app-switch";

/**
 * Inline in <head>: when the page opens at the end of a switch, cover it with the destination colour before the first
 * paint; {@link AppArrival} then puts the scene over it and removes the cover.
 */
export const appSwitchInitScript = `try{var s=JSON.parse(sessionStorage.getItem("${KEY}")||"null");if(s&&Date.now()-s.at<15000)document.documentElement.setAttribute("data-switching",s.to)}catch(e){}`;

/** The brand scene shown while one application hands over to the other. */
function Scene({ app, label, phase }: { app: App; label: string; phase: "build" | "hold" | "leave" }) {
  return (
    <div
      className={`${styles.scene} ${app === "atelys" ? styles.atelys : styles.mystix} ${styles[phase]}`}
      role="status"
      aria-live="polite"
    >
      <div className={styles.center}>
        {app === "atelys" ? (
          <span className={styles.trame}>
            <AtelysMark size={88} apex="#B57BFF" />
          </span>
        ) : (
          <span className={styles.tile}>
            <Monogram />
          </span>
        )}
        <span className={styles.name}>{app}</span>
        <span className={styles.label}>{label}</span>
        <span className={styles.track} aria-hidden="true">
          <span />
        </span>
      </div>
    </div>
  );
}

/**
 * Link from one application to the other (Mystix ↔ Atelys). The two shells are two documents (the root layout picks
 * the shell per request), so the switch is a full navigation. On click the destination brand takes the screen while
 * the next document loads; {@link AppArrival} in the destination shell then lifts it, so the hand-over reads as one motion.
 */
export function AppSwitchLink({
  href,
  to,
  label,
  className,
  ariaLabel,
  children,
}: {
  href: string;
  to: App;
  label: string;
  className?: string;
  ariaLabel?: string;
  children: ReactNode;
}) {
  const [leaving, setLeaving] = useState(false);
  return (
    <>
      <Link
        href={href}
        className={className}
        aria-label={ariaLabel}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
          e.preventDefault();
          try {
            sessionStorage.setItem(KEY, JSON.stringify({ to, label, at: Date.now() }));
          } catch {
            // Private mode: the arrival side simply does not animate.
          }
          setLeaving(true);
          // Let the first frames of the scene paint, then hand over to the other document.
          window.setTimeout(() => window.location.assign(href), 360);
        }}
      >
        {children}
      </Link>
      {leaving && createPortal(<Scene app={to} label={label} phase="build" />, document.body)}
    </>
  );
}

/** Placed in each shell: when we arrive from the other application, finish the scene and reveal the page. */
export function AppArrival({ app }: { app: App }) {
  const [scene, setScene] = useState<{ label: string; phase: "build" | "hold" | "leave" } | null>(null);
  // Read once and kept in a ref: effects may run twice (React strict mode), the stored flag is consumed once.
  const arrival = useRef<{ to: App; label: string; at: number } | null | undefined>(undefined);
  // Layout effect: the scene is up before the first paint of the destination, so the page never flashes through.
  useLayoutEffect(() => {
    if (arrival.current === undefined) {
      arrival.current = null;
      try {
        const raw = sessionStorage.getItem(KEY);
        sessionStorage.removeItem(KEY);
        if (raw) arrival.current = JSON.parse(raw);
      } catch {
        // No storage: no arrival scene.
      }
    }
    const switching = arrival.current;
    if (!switching || switching.to !== app || Date.now() - switching.at > 15000) {
      document.documentElement.removeAttribute("data-switching");
      return;
    }
    // Keep the scene on screen for a beat, so the brand registers even when the page loaded instantly.
    const elapsed = Date.now() - switching.at;
    const hold = Math.max(280, 900 - elapsed);
    setScene({ label: switching.label, phase: "hold" });
    // The scene is now on top: the head cover can go.
    window.requestAnimationFrame(() => document.documentElement.removeAttribute("data-switching"));
    const lift = window.setTimeout(() => setScene({ label: switching.label, phase: "leave" }), hold);
    const done = window.setTimeout(() => setScene(null), hold + 420);
    return () => {
      window.clearTimeout(lift);
      window.clearTimeout(done);
    };
  }, [app]);
  return scene ? createPortal(<Scene app={app} label={scene.label} phase={scene.phase} />, document.body) : null;
}
