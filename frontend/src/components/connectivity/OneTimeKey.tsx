"use client";

import { useState } from "react";
import portal from "@/app/[lang]/portal.module.css";
import styles from "@/components/channels/channels.module.css";

/** A secret shown once: selectable, copyable, never stored by the page. */
export function OneTimeKey({
  title,
  lead,
  value,
  copyLabel,
  copiedLabel,
}: {
  title: string;
  lead: string;
  value: string;
  copyLabel: string;
  copiedLabel: string;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <div className={styles.alert} role="status" style={{ marginTop: 12 }}>
      <strong style={{ display: "block" }}>{title}</strong>
      <span style={{ display: "block", margin: "4px 0 8px" }}>{lead}</span>
      <code className={styles.mono} style={{ display: "block", wordBreak: "break-all", userSelect: "all" }} dir="ltr">
        {value}
      </code>
      <button
        type="button"
        className={`${portal.btn} ${portal.btnSm}`}
        style={{ marginTop: 8 }}
        onClick={() => {
          void navigator.clipboard?.writeText(value).then(() => setCopied(true));
        }}
      >
        {copied ? copiedLabel : copyLabel}
      </button>
    </div>
  );
}
