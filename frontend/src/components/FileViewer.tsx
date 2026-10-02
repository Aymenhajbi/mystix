"use client";

import { useRef, useState, type KeyboardEvent } from "react";
import styles from "@/app/[lang]/portal.module.css";

export type ViewerFile = {
  kind: string;
  label: string;
  name: string;
  description: string;
  /** Lines already escaped and highlighted on the server. */
  lines: string[] | null;
  /** Exact text for the copy button. */
  text: string | null;
  sizeLabel: string;
  linesLabel: string;
};

type Labels = { title: string; copy: string; copied: string; copyFailed: string; unavailable: string };

/** Tabbed IN / canonical / OUT viewer of the Cockpit mockup, with line numbers. */
export function FileViewer({ files, initial, labels }: { files: ViewerFile[]; initial: string; labels: Labels }) {
  const [current, setCurrent] = useState(initial);
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const file = files.find((f) => f.kind === current) ?? files[0];

  const select = (index: number) => {
    const target = files[(index + files.length) % files.length];
    setCurrent(target.kind);
    setCopyState("idle");
    tabs.current[(index + files.length) % files.length]?.focus();
  };

  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const rtl = document.documentElement.dir === "rtl";
    if (e.key === (rtl ? "ArrowLeft" : "ArrowRight")) select(index + 1);
    else if (e.key === (rtl ? "ArrowRight" : "ArrowLeft")) select(index - 1);
    else if (e.key === "Home") select(0);
    else if (e.key === "End") select(files.length - 1);
    else return;
    e.preventDefault();
  };

  const copy = async () => {
    if (!file.text) return;
    try {
      await navigator.clipboard.writeText(file.text);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  };

  return (
    <section className={styles.panel} aria-label={labels.title}>
      <div className={styles.ftabs} role="tablist" aria-label={labels.title}>
        {files.map((f, i) => (
          <button
            key={f.kind}
            ref={(el) => {
              tabs.current[i] = el;
            }}
            type="button"
            role="tab"
            id={`tab-${f.kind}`}
            aria-selected={f.kind === file.kind}
            aria-controls={`panel-${f.kind}`}
            tabIndex={f.kind === file.kind ? 0 : -1}
            className={styles.ftab}
            onClick={() => select(i)}
            onKeyDown={(e) => onTabKey(e, i)}
          >
            {f.label}
          </button>
        ))}
      </div>
      <div className={styles.fbar}>
        <span className={styles.mono}>{file.name}</span>
        <span>{file.description}</span>
        <span className={styles.right}>
          {file.lines && (
            <span className={styles.muted}>
              {file.linesLabel} · {file.sizeLabel}
            </span>
          )}
          {file.text && (
            <button type="button" className={`${styles.btn} ${styles.btnSm}`} onClick={copy} aria-live="polite">
              {copyState === "copied" ? labels.copied : copyState === "failed" ? labels.copyFailed : labels.copy}
            </button>
          )}
        </span>
      </div>
      <div
        className={styles.viewer}
        role="tabpanel"
        id={`panel-${file.kind}`}
        aria-labelledby={`tab-${file.kind}`}
        tabIndex={0}
      >
        {file.lines ? (
          file.lines.map((html, i) => (
            <div className={styles.ln} key={i}>
              <span className={styles.no} aria-hidden="true">
                {i + 1}
              </span>
              <span dangerouslySetInnerHTML={{ __html: html || " " }} />
            </div>
          ))
        ) : (
          <p className={styles.empty}>{labels.unavailable}</p>
        )}
      </div>
    </section>
  );
}
