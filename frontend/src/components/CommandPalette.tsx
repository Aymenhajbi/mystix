"use client";

import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import styles from "@/app/[lang]/portal.module.css";
import { Icon } from "./Icons";

export type PaletteItem = { kind: string; label: string; extra: string; href: string; keywords: string };

type Labels = { trigger: string; triggerLabel: string; dialog: string; placeholder: string; empty: string; hint: string };

/** Ctrl K / Cmd K search over pages and the company's invoices, as in the Cockpit mockup. */
export function CommandPalette({ items, labels }: { items: PaletteItem[]; labels: Labels }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matches = q ? items.filter((i) => i.keywords.toLowerCase().includes(q)) : items;
    return matches.slice(0, 30);
  }, [items, query]);

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setQuery("");
        setActive(0);
        setOpen((o) => !o);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const close = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  const go = (item: PaletteItem | undefined) => {
    if (!item) return;
    setOpen(false);
    router.push(item.href);
  };

  const onInputKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(a + 1, results.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      go(results[active]);
    } else if (e.key === "Escape") {
      e.preventDefault();
      close();
    }
  };

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={styles.search}
        onClick={() => {
          setQuery("");
          setActive(0);
          setOpen(true);
        }}
        aria-label={labels.triggerLabel}
        aria-haspopup="dialog"
      >
        <Icon name="search" />
        <span>{labels.trigger}</span>
        <kbd className={styles.kbd}>Ctrl K</kbd>
      </button>
      {open && (
        <>
          <div className={styles.scrim} onClick={close} aria-hidden="true" />
          <div className={styles.palette} role="dialog" aria-modal="true" aria-label={labels.dialog}>
            <input
              ref={inputRef}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setActive(0);
              }}
              onKeyDown={onInputKey}
              placeholder={labels.placeholder}
              aria-label={labels.placeholder}
              role="combobox"
              aria-expanded="true"
              aria-controls="palette-results"
              aria-activedescendant={results[active] ? `palette-${active}` : undefined}
              autoComplete="off"
            />
            <ul id="palette-results" role="listbox">
              {results.length === 0 && <li className={styles.muted}>{labels.empty}</li>}
              {results.map((item, i) => (
                <li
                  key={item.href}
                  id={`palette-${i}`}
                  role="option"
                  aria-selected={i === active}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => go(item)}
                >
                  <span className={styles.kind}>{item.kind}</span>
                  <bdi>{item.label}</bdi>
                  <span className={styles.extra}>{item.extra}</span>
                </li>
              ))}
            </ul>
            <div className={styles.paletteHint}>{labels.hint}</div>
          </div>
        </>
      )}
    </>
  );
}
