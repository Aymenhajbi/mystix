import { Suspense } from "react";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { fetchBackendHealth } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";
import { getDictionary, type Dictionary } from "./dictionaries";
import styles from "./portal.module.css";

async function BackendStatus({ dict }: { dict: Dictionary }) {
  await connection();
  const { status } = await fetchBackendHealth();
  const label = { UP: dict.home.up, DOWN: dict.home.down, UNREACHABLE: dict.home.unreachable }[status];
  return (
    <p className={styles.status} role="status">
      <span className={`${styles.dot} ${status === "UP" ? styles.up : styles.down}`} aria-hidden="true" />
      {dict.home.api} : {label}
    </p>
  );
}

export default async function Home({ params }: PageProps<"/[lang]">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  const dict = await getDictionary(lang);

  return (
    <main className={styles.main}>
      <h1 className={styles.title}>Mystix</h1>
      <p className={styles.lead}>{dict.home.lead}</p>
      <section className={styles.card} aria-labelledby="platform-status">
        <h2 id="platform-status">{dict.home.statusTitle}</h2>
        <Suspense fallback={<p role="status">{dict.home.checking}</p>}>
          <BackendStatus dict={dict} />
        </Suspense>
      </section>
      <Link href={`/${lang}/invoices`} className={styles.button}>
        {dict.home.seeInvoices}
      </Link>
    </main>
  );
}
