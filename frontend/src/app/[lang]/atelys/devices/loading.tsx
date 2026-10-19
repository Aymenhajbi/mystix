"use client";

import { useParams } from "next/navigation";
import { DeviceIcon } from "./icons";
import d from "./devices.module.css";

// A loading file receives no params: the language comes from the URL on the client. Short texts kept here so the
// full dictionaries are not shipped to the browser.
const TEXT = {
  fr: {
    title: "Préparation de la vue des terminaux",
    steps: ["Interrogation des terminaux enregistrés", "Lecture des derniers tests de scan", "Calcul de l'état de chaque terminal"],
    hint: "Les terminaux se signalent eux-mêmes à chaque démarrage d'Atelys Mobile. Rien ne leur est envoyé pendant ce chargement.",
  },
  ar: {
    title: "تحضير عرض الأجهزة",
    steps: ["استعلام الأجهزة المسجلة", "قراءة آخر اختبارات المسح", "حساب حالة كل جهاز"],
    hint: "تُعرّف الأجهزة بنفسها عند كل تشغيل لـ Atelys Mobile. لا يُرسل إليها شيء أثناء هذا التحميل.",
  },
} as const;

/** Shown while the terminals load: says what is being done, step by step. */
export default function Loading() {
  const params = useParams<{ lang: string }>();
  const L = params?.lang === "ar" ? TEXT.ar : TEXT.fr;
  return (
    <div className={d.loading} role="status" aria-live="polite" aria-busy="true">
      <div className={d.loadingCard}>
        <div className={d.radar}>
          <DeviceIcon name="radar" />
        </div>
        <div>
          <p className={d.loadingTitle}>{L.title}</p>
          <ol className={d.loadingSteps}>
            {L.steps.map((step) => (
              <li key={step} className={d.loadingStep}>
                {step}
              </li>
            ))}
          </ol>
          <p className={d.loadingHint}>{L.hint}</p>
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
