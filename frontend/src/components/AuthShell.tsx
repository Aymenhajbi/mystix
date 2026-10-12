import type { ReactNode } from "react";
import styles from "@/app/[lang]/auth.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { Locale } from "@/lib/i18n";
import { Monogram, Wordmark } from "./Icons";
import { LocaleSwitch } from "./LocaleSwitch";
import { LoginArt } from "./LoginArt";
import { ThemeToggle } from "./ThemeToggle";

/** Two-panel frame of the sign-in pages: the form, and the floating art card on wide screens. */
export function AuthShell({
  lang,
  dict,
  eyebrow,
  title,
  lead,
  children,
}: {
  lang: Locale;
  dict: Dictionary;
  eyebrow: string;
  title: string;
  lead: string;
  children: ReactNode;
}) {
  const a = dict.auth;
  return (
    <div className={styles.page}>
      <section className={styles.formSide}>
        <header className={styles.formHead}>
          <span className={styles.logo} aria-hidden="true">
            <Monogram />
          </span>
          <Wordmark />
          <span className={styles.spacer} />
          <ThemeToggle label={dict.top.theme} names={dict.top.themeNames} />
          <LocaleSwitch current={lang} label={dict.nav.switchTo} ariaLabel={dict.nav.switchToLabel} />
        </header>
        <main id="main" className={styles.formBody}>
          <p className={styles.eyebrow}>{eyebrow}</p>
          <h1 className={styles.title}>{title}</h1>
          <p className={styles.lead}>{lead}</p>
          {children}
          <p className={styles.note}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <rect x="4" y="10" width="16" height="11" rx="2.5" />
              <path d="M8 10V7a4 4 0 0 1 8 0v3" />
            </svg>
            {a.sessionNote}
          </p>
        </main>
        <footer className={styles.formFoot}>
          <span>{a.brandTag}</span>
          <span className={styles.simChip}>{dict.top.simulated}</span>
        </footer>
      </section>
      <aside className={styles.artSide} aria-label={a.artLabel}>
        <LoginArt label={a.artLabel} caption={a.artCaption} chips={a.artChips} />
      </aside>
    </div>
  );
}
