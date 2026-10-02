import { Suspense } from "react";
import { connection } from "next/server";
import { fetchBackendHealth } from "@/lib/api";
import styles from "./page.module.css";

const statusLabels = {
  UP: "Opérationnel",
  DOWN: "Dégradé",
  UNREACHABLE: "Injoignable",
} as const;

async function BackendStatus() {
  await connection();
  const { status } = await fetchBackendHealth();
  return (
    <p className={styles.status} role="status">
      <span className={`${styles.dot} ${status === "UP" ? styles.up : styles.down}`} aria-hidden="true" />
      API Mystix : {statusLabels[status]}
    </p>
  );
}

export default function Home() {
  return (
    <main className={styles.main}>
      <h1 className={styles.brand}>Mystix</h1>
      <p className={styles.lead}>Intégration EDI, facturation électronique et clearance DGI.</p>

      <aside className={styles.notice}>
        <strong>Clearance simulée.</strong> Les spécifications techniques de la DGI ne sont pas encore publiées :
        aucune facture n&apos;est transmise à la DGI.
      </aside>

      <section className={styles.card} aria-labelledby="platform-status">
        <h2 id="platform-status">État de la plateforme</h2>
        <Suspense fallback={<p role="status">Vérification de l&apos;API…</p>}>
          <BackendStatus />
        </Suspense>
      </section>
    </main>
  );
}
