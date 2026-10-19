import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { t, type Dictionary } from "@/app/[lang]/dictionaries";
import { devices, type DeviceProfile, type ScanAdapter } from "@/lib/devices";
import { hasLocale, type Locale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";
import pstyles from "../../../portal.module.css";
import s from "../../atelys.module.css";
import { ActionForm, ApiDown, Badge, Head, Notice, one, when } from "../../ui";
import { DeviceIcon, adapterIcon } from "../icons";
import { adapterName } from "../shared";
import d from "../devices.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/devices/profiles">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.devices.profiles.title} · Atelys` } : {};
}

export default async function ProfilesPage({ params, searchParams }: PageProps<"/[lang]/atelys/devices/profiles">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const P = dict.atelys.devices.profiles;
  const query = await searchParams;
  const [profiles, adapters] = await Promise.all([devices.profiles(), devices.adapters()]);
  if (profiles.kind !== "ok") return <ApiDown dict={dict} kind={profiles.kind} />;
  if (adapters.kind !== "ok") return <ApiDown dict={dict} kind={adapters.kind} />;
  const page = `/${lang}/atelys/devices/profiles`;

  return (
    <>
      <nav className={pstyles.crumbs} aria-label={dict.clients.breadcrumb}>
        <Link href={`/${lang}/atelys/devices`}>{P.back}</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{P.title}</span>
      </nav>
      <Head eyebrow={dict.atelys.nav.admin} title={P.title} lead={P.lead}>
        <a href="#new" className={s.ghostBtn}>
          {P.new}
        </a>
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />

      {profiles.data.length === 0 ? (
        <section className={pstyles.panel}>
          <div className={d.empty}>
            <div className={d.emptyArt}>
              <DeviceIcon name="profile" />
            </div>
            <p>{P.empty}</p>
          </div>
        </section>
      ) : (
        <div className={d.profiles}>
          {profiles.data.map((p, i) => (
            <details
              key={p.id}
              className={`${d.profile} ${p.status === "RETIRED" ? d.profileRetired : ""}`}
              style={{ animationDelay: `${Math.min(i, 8) * 50}ms` }}
            >
              <summary>
                <span className={d.tileIcon}>
                  <DeviceIcon name={adapterIcon(p.recommendedAdapter)} />
                </span>
                <div style={{ minWidth: 0 }}>
                  <div className={d.cardName}>
                    {p.name} <span className={d.version}>{t(P.version, { n: p.version })}</span>
                  </div>
                  <div className={d.cardSub}>
                    {P.scope[p.scope]} · {p.manufacturer}
                    {p.models.length > 0 && ` · ${p.models.join(", ")}`} → {adapterName(dict, p.recommendedAdapter)}
                  </div>
                </div>
                <Badge tone={p.status === "VALIDATED" ? "ok" : p.status === "EXPERIMENTAL" ? "warn" : "muted"}>
                  {P.status[p.status]}
                </Badge>
                <DeviceIcon name="chevron" className={d.chevron} />
              </summary>
              <div className={d.profileBody}>
                <p className={d.controlHint} style={{ marginBlock: 12 }}>
                  {t(P.updated, { at: when(lang, p.updatedAt), by: p.updatedBy ?? "—" })}
                </p>
                {p.status === "RETIRED" ? (
                  <p className={d.controlHint}>{P.retiredNote}</p>
                ) : (
                  <>
                    <ProfileForm lang={lang} dict={dict} adapters={adapters.data} profile={p} page={page} />
                    <ActionForm lang={lang} path={`/api/v1/atelys/device-profiles/${p.id}/retire`} back={page}>
                      <button type="submit" className={s.ghostBtn} style={{ marginTop: 10 }}>
                        {P.retire}
                      </button>
                    </ActionForm>
                  </>
                )}
              </div>
            </details>
          ))}
        </div>
      )}

      <section className={pstyles.panel} style={{ marginTop: 18 }} id="new">
        <div className={pstyles.panelHead}>
          <h2>{P.new}</h2>
        </div>
        <div style={{ padding: "0 18px 18px" }}>
          <ProfileForm lang={lang} dict={dict} adapters={adapters.data} page={page} />
        </div>
      </section>
    </>
  );
}

function ProfileForm({
  lang,
  dict,
  adapters,
  profile,
  page,
}: {
  lang: Locale;
  dict: Dictionary;
  adapters: ScanAdapter[];
  profile?: DeviceProfile;
  page: string;
}) {
  const P = dict.atelys.devices.profiles;
  const F = P.fields;
  return (
    <ActionForm
      lang={lang}
      method={profile ? "PUT" : "POST"}
      path={profile ? `/api/v1/atelys/device-profiles/${profile.id}` : "/api/v1/atelys/device-profiles"}
      back={page}
      className={s.body}
    >
      <div className={s.formGrid}>
        <label>
          {F.name}
          <input name="f.name" required maxLength={80} defaultValue={profile?.name} />
        </label>
        <label>
          {F.scope}
          <select name="f.scope" defaultValue={profile?.scope ?? "MODEL"}>
            {(["MODEL", "MANUFACTURER", "INSTALLATION"] as const).map((x) => (
              <option key={x} value={x}>
                {P.scope[x]}
              </option>
            ))}
          </select>
        </label>
        <label>
          {F.manufacturer}
          <input name="f.manufacturer" required maxLength={80} defaultValue={profile?.manufacturer ?? "Honeywell"} />
        </label>
        <label>
          {F.models}
          <input name="csv.models" maxLength={400} defaultValue={profile?.models.join(", ")} placeholder="CK67" />
        </label>
        <label>
          {F.installation}
          <input name="f.installationId" maxLength={64} defaultValue={profile?.installationId ?? ""} />
        </label>
        <label>
          {F.minSdk}
          <input name="n.minSdk" type="number" min="1" max="99" defaultValue={profile?.minSdk ?? ""} />
        </label>
        <label>
          {F.maxSdk}
          <input name="n.maxSdk" type="number" min="1" max="99" defaultValue={profile?.maxSdk ?? ""} />
        </label>
        <label>
          {F.adapter}
          <select name="f.recommendedAdapter" defaultValue={profile?.recommendedAdapter ?? adapters[0]?.id}>
            {adapters.map((a) => (
              <option key={a.id} value={a.id}>
                {adapterName(dict, a.id)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {F.confidence}
          <select name="f.confidence" defaultValue={profile?.confidence ?? "MEDIUM"}>
            {(["LOW", "MEDIUM", "HIGH"] as const).map((x) => (
              <option key={x} value={x}>
                {P.confidence[x]}
              </option>
            ))}
          </select>
        </label>
        <label>
          {F.status}
          <select name="f.status" defaultValue={profile?.status ?? "EXPERIMENTAL"}>
            {(["EXPERIMENTAL", "VALIDATED"] as const).map((x) => (
              <option key={x} value={x}>
                {P.status[x]}
              </option>
            ))}
          </select>
        </label>
        <label style={{ gridColumn: "1 / -1" }}>
          {F.prerequisites}
          <textarea name="f.prerequisites" rows={2} maxLength={2000} defaultValue={profile?.prerequisites ?? ""} />
        </label>
        <label style={{ gridColumn: "1 / -1" }}>
          {F.testProcedure}
          <textarea name="f.testProcedure" rows={3} maxLength={4000} defaultValue={profile?.testProcedure ?? ""} />
        </label>
      </div>
      <div className={pstyles.formActions} style={{ marginTop: 12 }}>
        <button type="submit" className={s.flashBtn}>
          {profile ? P.save : P.create}
        </button>
      </div>
    </ActionForm>
  );
}
