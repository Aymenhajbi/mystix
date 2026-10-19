import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import { DeviceIcon } from "./icons";
import d from "./devices.module.css";

/** Shown while the terminals load: says what is being done, step by step. */
export default async function Loading({ params }: { params: Promise<{ lang: string }> }) {
  const { lang } = await params;
  const L = hasLocale(lang) ? (await getDictionary(lang)).atelys.devices.loading : null;
  return (
    <div className={d.loading} role="status" aria-live="polite" aria-busy="true">
      <div className={d.loadingCard}>
        <div className={d.radar}>
          <DeviceIcon name="radar" />
        </div>
        <div>
          <p className={d.loadingTitle}>{L?.title ?? "…"}</p>
          <ol className={d.loadingSteps}>
            {(L?.steps ?? []).map((step) => (
              <li key={step} className={d.loadingStep}>
                {step}
              </li>
            ))}
          </ol>
          {L && <p className={d.loadingHint}>{L.hint}</p>}
        </div>
      </div>
      <div className={d.skeletons} aria-hidden="true">
        {[0, 1, 2].map((i) => (
          <div key={i} className={d.skeleton} />
        ))}
      </div>
    </div>
  );
}
