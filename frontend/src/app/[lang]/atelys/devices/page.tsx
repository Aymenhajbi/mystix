import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { notFound } from "next/navigation";
import { devices, type DeviceHealth } from "@/lib/devices";
import { hasLocale } from "@/lib/i18n";
import { getDictionary } from "../../dictionaries";
import pstyles from "../../portal.module.css";
import s from "../atelys.module.css";
import { ApiDown, Head } from "../ui";
import { DeviceIcon, adapterIcon, healthIcon } from "./icons";
import { HealthBadge, adapterName, ago, requestTime } from "./shared";
import d from "./devices.module.css";

export async function generateMetadata({ params }: PageProps<"/[lang]/atelys/devices">): Promise<Metadata> {
  const { lang } = await params;
  return hasLocale(lang) ? { title: `${(await getDictionary(lang)).atelys.devices.title} · Atelys` } : {};
}

const HEALTHS: DeviceHealth[] = ["OK", "ACTION_REQUIRED", "ERROR", "OFFLINE"];
const HOW_ICONS = ["download", "radar", "scan", "shield"] as const;

export default async function DevicesPage({ params }: PageProps<"/[lang]/atelys/devices">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  await connection();
  const dict = await getDictionary(lang);
  const D = dict.atelys.devices;
  const list = await devices.list();
  if (list.kind !== "ok") return <ApiDown dict={dict} kind={list.kind} />;
  const all = list.data;
  const now = requestTime();
  const count = (h: DeviceHealth) => all.filter((x) => x.health === h).length;

  return (
    <>
      <Head eyebrow={dict.atelys.nav.admin} title={D.title} lead={D.lead}>
        <Link href={`/${lang}/atelys/devices/profiles`} className={s.ghostBtn}>
          {D.profilesLink}
        </Link>
      </Head>

      <div className={d.summary}>
        <div className={`${d.tile} ${d["tone-total"]}`}>
          <span className={d.tileIcon}>
            <DeviceIcon name="fleet" />
          </span>
          <div>
            <div className={d.tileValue}>{all.length}</div>
            <div className={d.tileLabel}>{D.summary.total}</div>
          </div>
        </div>
        {HEALTHS.map((h) => (
          <div key={h} className={`${d.tile} ${d[`tone-${h}`]}`} title={D.healthHint[h]}>
            <span className={d.tileIcon}>
              <DeviceIcon name={healthIcon[h]} />
            </span>
            <div>
              <div className={d.tileValue}>{count(h)}</div>
              <div className={d.tileLabel}>{D.summary[h]}</div>
            </div>
          </div>
        ))}
      </div>

      {all.length === 0 ? (
        <section className={pstyles.panel}>
          <div className={d.empty}>
            <div className={d.emptyArt}>
              <DeviceIcon name="terminal" />
            </div>
            <h2>{D.empty.title}</h2>
            <p>{D.empty.text}</p>
          </div>
        </section>
      ) : (
        <div className={d.cards}>
          {all.map((x, i) => (
            <Link
              key={x.id}
              href={`/${lang}/atelys/devices/${x.id}`}
              className={d.card}
              style={{ animationDelay: `${Math.min(i, 8) * 50}ms` }}
            >
              <div className={d.cardTop}>
                <span className={d.deviceIcon}>
                  <DeviceIcon name="terminal" />
                  <span className={`${d.dot} ${d[`tone-${x.health}`]}`} aria-hidden="true" />
                </span>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div className={d.cardName}>{x.label ?? `${x.manufacturer} ${x.model}`}</div>
                  <div className={d.cardSub}>
                    {x.label ? `${x.manufacturer} ${x.model} · ` : ""}
                    {D.fields.installation} {x.installationId.slice(0, 8)}
                  </div>
                </div>
                <HealthBadge health={x.health} dict={dict} />
              </div>
              <dl className={d.cardMeta}>
                <dt>{D.columns.android}</dt>
                <dd>
                  {x.androidRelease ?? "—"} · API {x.sdkInt}
                </dd>
                <dt>{D.columns.adapter}</dt>
                <dd>
                  <span className={d.chip}>
                    <DeviceIcon name={adapterIcon(x.activeAdapter)} />
                    {adapterName(dict, x.activeAdapter)}
                  </span>
                </dd>
              </dl>
              <div className={d.cardFoot}>
                <span>{D.healthHint[x.health]}</span>
                <span className={d.chip}>
                  <DeviceIcon name="clock" />
                  {ago(lang, x.lastSeenAt, now)}
                </span>
              </div>
            </Link>
          ))}
        </div>
      )}

      <section className={pstyles.panel} style={{ marginTop: 18 }}>
        <div className={d.how}>
          <h2 className={d.howTitle}>{D.how.title}</h2>
          <ol className={d.howSteps}>
            {D.how.steps.map((step, i) => (
              <li key={step.title} className={d.howStep}>
                <span className={d.howIcon}>
                  <DeviceIcon name={HOW_ICONS[i] ?? "scan"} />
                </span>
                <div>
                  <strong>{step.title}</strong>
                  <p>{step.text}</p>
                </div>
              </li>
            ))}
          </ol>
        </div>
      </section>
    </>
  );
}
