import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { t } from "@/app/[lang]/dictionaries";
import { devices } from "@/lib/devices";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../../dictionaries";
import pstyles from "../../../portal.module.css";
import s from "../../atelys.module.css";
import { ActionForm, ApiDown, Head, Notice, one, when } from "../../ui";
import { DeviceIcon, adapterIcon } from "../icons";
import { HealthBadge, adapterName, ago, requestTime, reasonText } from "../shared";
import d from "../devices.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/devices/[id]">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.devices.title} · Atelys` } : {};
}

export default async function DevicePage({ params, searchParams }: PageProps<"/[lang]/atelys/devices/[id]">) {
  const { lang, id } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const D = dict.atelys.devices;
  const query = await searchParams;
  const [view, adapters] = await Promise.all([devices.get(id), devices.adapters()]);
  if (view.kind === "not-found") notFound();
  if (view.kind !== "ok") return <ApiDown dict={dict} kind={view.kind} />;
  if (adapters.kind !== "ok") return <ApiDown dict={dict} kind={adapters.kind} />;
  const { device: x, diagnostics, audit } = view.data;
  const page = `/${lang}/atelys/devices/${x.id}`;
  const api = `/api/v1/atelys/devices/${x.id}`;
  const now = requestTime();
  const honeywell = x.manufacturer.trim().toLowerCase() === "honeywell";
  const usable = adapters.data.filter((a) => !a.honeywellOnly || honeywell);
  const adapterTexts = D.adapters as Record<string, { name: string; text: string; badge: string }>;

  // Where the terminal stands: detected, method proposed, read tested, operational.
  const done = [
    true,
    Boolean(x.recommendedAdapter || x.activeAdapter),
    Boolean(x.activeAdapter) || diagnostics.some((g) => !g.simulated),
    x.health === "OK",
  ];
  const now_ = done.findIndex((v) => !v);

  return (
    <>
      <nav className={pstyles.crumbs} aria-label={dict.clients.breadcrumb}>
        <Link href={`/${lang}/atelys/devices`}>{D.title}</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{x.label ?? x.model}</span>
      </nav>
      <Head
        eyebrow={dict.atelys.nav.admin}
        title={x.label ?? `${x.manufacturer} ${x.model}`}
        lead={`${x.manufacturer} ${x.model} · Android ${x.androidRelease ?? "?"} · ${D.fields.installation} ${x.installationId.slice(0, 8)}`}
      >
        <HealthBadge health={x.health} dict={dict} />
      </Head>
      <Notice error={one(query.error)} ok={one(query.ok)} dict={dict} />

      <section className={pstyles.panel}>
        <ol className={d.journey} aria-label={D.journey.label}>
          {D.journey.steps.map((step, i) => (
            <li
              key={step}
              className={`${d.journeyStep} ${done[i] ? d.journeyDone : i === now_ ? d.journeyNow : ""}`}
              aria-current={i === now_ ? "step" : undefined}
            >
              <span className={d.journeyMark}>
                <DeviceIcon name={done[i] ? "check" : (["radar", "tag", "scan", "shield"] as const)[i]} />
              </span>
              {step}
            </li>
          ))}
        </ol>
      </section>

      <div className={d.detailGrid}>
        <div className={pstyles.stack}>
          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{D.fields.resolution}</h2>
              <div className={pstyles.right}>
                <span className={d.chip}>
                  <DeviceIcon name="clock" />
                  {ago(lang, x.lastSeenAt, now)}
                </span>
              </div>
            </div>
            <dl className={d.facts}>
              <dt>{D.fields.resolution}</dt>
              <dd>
                <strong>{x.resolutionState ? D.resolution[x.resolutionState] : "—"}</strong>
              </dd>
              <dt>{D.fields.recommended}</dt>
              <dd>{adapterName(dict, x.recommendedAdapter)}</dd>
              <dt>{D.fields.active}</dt>
              <dd>
                <span className={d.chip}>
                  <DeviceIcon name={adapterIcon(x.activeAdapter)} />
                  {adapterName(dict, x.activeAdapter)}
                </span>
              </dd>
              <dt>{D.fields.validatedAt}</dt>
              <dd>{when(lang, x.validatedAt)}</dd>
              <dt>{D.fields.preferred}</dt>
              <dd>
                {adapterName(dict, x.preferredAdapter)}
                {x.preferredBy && <span className={pstyles.muted}> · {x.preferredBy}</span>}
              </dd>
            </dl>
            {x.resolutionReasons.length > 0 && (
              <>
                <div className={pstyles.panelHead} style={{ borderBlockStart: "1px solid var(--line)" }}>
                  <h2>{D.fields.reasons}</h2>
                </div>
                <ul className={d.reasons} style={{ paddingBlockStart: 14 }}>
                  {x.resolutionReasons.map((r, i) => (
                    <li key={r} className={d.reason} style={{ animationDelay: `${i * 60}ms` }}>
                      <DeviceIcon name="info" />
                      {reasonText(dict, r)}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>

          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{D.diagnostics.title}</h2>
            </div>
            {diagnostics.length === 0 ? (
              <p className={pstyles.muted} style={{ padding: "14px 18px" }}>
                {D.diagnostics.empty}
              </p>
            ) : (
              <ol className={d.timeline}>
                {diagnostics.map((g) => (
                  <li key={g.id} className={d.event}>
                    <span className={`${d.eventMark} ${g.outcome === "PASSED" ? d.eventOk : g.outcome === "CANCELLED" ? "" : d.eventBad}`}>
                      <DeviceIcon name={g.outcome === "PASSED" ? "check" : "error"} />
                    </span>
                    <div>
                      <div className={d.eventTitle}>
                        {D.diagnostics.outcome[g.outcome]} · {adapterName(dict, g.adapterId)}
                        {g.simulated ? (
                          <span className={d.simTag}>{D.diagnostics.simulated}</span>
                        ) : (
                          <span className={d.realTag}>{D.diagnostics.physical}</span>
                        )}
                      </div>
                      <div className={d.eventSub}>
                        {when(lang, g.finishedAt)}
                        {g.symbology && ` · ${g.symbology}`}
                        {g.dataLength != null && ` · ${t(D.diagnostics.chars, { n: g.dataLength })}`}
                        {g.errorCode && ` · ${g.errorCode}`}
                      </div>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>

          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{D.audit.title}</h2>
            </div>
            {audit.length === 0 ? (
              <p className={pstyles.muted} style={{ padding: "14px 18px" }}>
                {D.audit.empty}
              </p>
            ) : (
              <ol className={d.timeline}>
                {audit.map((e) => (
                  <li key={e.id} className={d.event}>
                    <span className={d.eventMark}>
                      <DeviceIcon name="clock" />
                    </span>
                    <div>
                      <div className={d.eventTitle}>
                        {(D.audit.actions as Record<string, string>)[e.action] ?? e.action}
                      </div>
                      <div className={d.eventSub}>
                        {when(lang, e.at)} · {e.actor ?? "—"}
                        {e.detail && ` · ${e.detail}`}
                      </div>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>

        <div className={pstyles.stack}>
          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{D.actions.title}</h2>
            </div>
            <div className={d.controls}>
              <div className={d.control}>
                <ActionForm lang={lang} method="PUT" path={`${api}/label`} back={page}>
                  <label className={pstyles.field}>
                    {D.fields.label}
                    <span className={d.inline}>
                      <input name="f.label" defaultValue={x.label ?? ""} maxLength={80} placeholder={D.actions.labelPlaceholder} />
                      <button type="submit" className={s.ghostBtn}>
                        {D.actions.rename}
                      </button>
                    </span>
                  </label>
                </ActionForm>
              </div>

              <div className={d.control}>
                <ActionForm lang={lang} method="PUT" path={`${api}/preference`} back={page}>
                  <label className={pstyles.field}>
                    {D.actions.prefer}
                    <span className={d.inline}>
                      <select name="f.adapterId" defaultValue={x.preferredAdapter ?? "none"}>
                        <option value="none">{D.actions.noPreference}</option>
                        {usable.map((a) => (
                          <option key={a.id} value={a.id}>
                            {adapterTexts[a.id]?.name ?? a.name}
                            {a.experimental ? ` · ${adapterTexts[a.id]?.badge || ""}` : ""}
                          </option>
                        ))}
                      </select>
                      <button type="submit" className={s.ghostBtn}>
                        {D.actions.savePrefer}
                      </button>
                    </span>
                  </label>
                </ActionForm>
                <p className={d.controlHint}>{D.actions.preferHint}</p>
                <div className={pstyles.stack} style={{ gap: 8 }}>
                  {usable.map((a) => (
                    <div key={a.id} className={d.adapterCard}>
                      <DeviceIcon name={adapterIcon(a.id)} />
                      <div>
                        <strong>
                          {adapterTexts[a.id]?.name ?? a.name}{" "}
                          {a.experimental && <span className={d.simTag}>{adapterTexts[a.id]?.badge}</span>}
                        </strong>
                        <p>{adapterTexts[a.id]?.text}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              <div className={d.control}>
                <ActionForm lang={lang} path={`${api}/test-request`} back={page}>
                  <button type="submit" className={s.flashBtn}>
                    <DeviceIcon name="scan" className={d.btnIcon} />
                    {D.actions.requestTest}
                  </button>
                </ActionForm>
                {x.testRequestedAt ? (
                  <span className={d.pending}>
                    <DeviceIcon name="refresh" />
                    {t(D.actions.pendingTest, { at: when(lang, x.testRequestedAt) })}
                  </span>
                ) : (
                  <p className={d.controlHint}>{D.actions.requestTestHint}</p>
                )}
              </div>

              <div className={d.control}>
                <ActionForm lang={lang} path={`${api}/reset`} back={page}>
                  <button type="submit" className={s.ghostBtn}>
                    {D.actions.reset}
                  </button>
                </ActionForm>
                {x.resetRequestedAt ? (
                  <span className={d.pending}>
                    <DeviceIcon name="refresh" />
                    {t(D.actions.pendingReset, { at: when(lang, x.resetRequestedAt) })}
                  </span>
                ) : (
                  <p className={d.controlHint}>{D.actions.resetHint}</p>
                )}
                <p className={d.controlHint}>{D.actions.adminOnly}</p>
              </div>
            </div>
          </section>

          <section className={pstyles.panel}>
            <div className={pstyles.panelHead}>
              <h2>{D.fields.model}</h2>
            </div>
            <dl className={d.facts}>
              <dt>{D.fields.manufacturer}</dt>
              <dd>{x.manufacturer}</dd>
              <dt>{D.fields.brand}</dt>
              <dd>{x.brand ?? "—"}</dd>
              <dt>{D.fields.model}</dt>
              <dd>{x.model}</dd>
              <dt>{D.fields.product}</dt>
              <dd>{x.product ?? "—"}</dd>
              <dt>{D.fields.android}</dt>
              <dd>{x.androidRelease ?? "—"}</dd>
              <dt>{D.fields.api}</dt>
              <dd>{x.sdkInt}</dd>
              <dt>{D.fields.build}</dt>
              <dd className={d.mono}>{x.buildId ?? "—"}</dd>
              <dt>{D.fields.app}</dt>
              <dd>{x.appVersion ?? "—"}</dd>
              <dt>{D.fields.installation}</dt>
              <dd className={d.mono}>{x.installationId}</dd>
              <dt>{D.fields.lastSeenBy}</dt>
              <dd>{x.lastSeenBy ?? "—"}</dd>
              <dt>{D.fields.firstSeen}</dt>
              <dd>{when(lang, x.createdAt)}</dd>
              <dt>{D.fields.capabilities}</dt>
              <dd>
                <span className={d.chips}>
                  {x.capabilities.length === 0
                    ? "—"
                    : x.capabilities.map((c) => (
                        <span key={c} className={d.chip}>
                          {(D.capabilities as Record<string, string>)[c] ?? c}
                        </span>
                      ))}
                </span>
              </dd>
            </dl>
          </section>
        </div>
      </div>

      <p className={d.note}>
        <DeviceIcon name="info" />
        {D.hardwareNote}
      </p>
    </>
  );
}
