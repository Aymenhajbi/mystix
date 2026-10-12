import Link from "next/link";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import portal from "@/app/[lang]/portal.module.css";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import { getCompany, getForCompany, getPortalCompany, type Partner } from "@/lib/api";
import {
  addCertificateAction,
  deleteCertificateAction,
  deleteChannelAction,
} from "@/lib/channelActions";
import {
  certificateHealth,
  channelEndpoint,
  channelsApi,
  type Channel,
  type ChannelApp,
  type ChannelCertificate,
} from "@/lib/channels";
import { exchangeSheet } from "@/lib/exchangeSheet";
import { formatDateTime, type Locale } from "@/lib/i18n";
import { ChannelWizard } from "./ChannelWizard";
import { ConfirmButton } from "./ConfirmButton";
import styles from "./channels.module.css";

type Query = Record<string, string | string[] | undefined>;

type Common = {
  lang: Locale;
  dict: Dictionary;
  /** The environment chosen in Mystix; absent in Atelys, where the session environment applies. */
  companyId?: string;
  app: ChannelApp;
  /** URL of the channel list of this application. */
  base: string;
  crumbs?: ReactNode;
  query: Query;
};

const one = (query: Query, k: string) => (Array.isArray(query[k]) ? query[k]?.[0] : query[k]) as string | undefined;

function Unreachable({ dict }: { dict: Dictionary }) {
  return (
    <p className={portal.message} role="alert">
      {dict.common.apiUnreachable}
    </p>
  );
}

/** Success and error messages of the channel screens, from the query string. */
function Notice({ dict, query }: { dict: Dictionary; query: Query }) {
  const c = dict.channels;
  const error = one(query, "error");
  if (error) {
    const text =
      c.certificateErrors[error as keyof typeof c.certificateErrors] ??
      c.errors[error as keyof typeof c.errors] ??
      c.errors.default.replace("{code}", error);
    return (
      <p className={portal.message} role="alert">
        {text}
      </p>
    );
  }
  const ok = one(query, "created")
    ? c.created
    : one(query, "saved")
      ? c.saved
      : one(query, "deleted")
        ? c.deleted
        : one(query, "certificate")
          ? c.certificates.added
          : one(query, "certificateDeleted")
            ? c.certificates.removed
            : null;
  return ok ? (
    <p className={portal.notice} role="status">
      {ok}
    </p>
  ) : null;
}

async function partnersOf(companyId?: string) {
  const partners = await getForCompany<Partner[]>("/api/v1/partners", companyId);
  return partners.kind === "ok" ? partners.data.map((p) => ({ id: p.id, name: p.name })) : [];
}

function Glyph({ protocol, large }: { protocol: Channel["protocol"]; large?: boolean }) {
  return (
    <span className={`${styles.glyph} ${large ? styles.glyphLg : ""}`} aria-hidden="true">
      {protocol === "WEBHOOK" ? "HOOK" : protocol}
    </span>
  );
}

function StatusTag({ channel, dict }: { channel: Channel; dict: Dictionary }) {
  return (
    <span className={styles.tag}>
      <span className={`${styles.dot} ${styles[`dot${channel.status}`]}`} aria-hidden="true" />
      {dict.channels.statuses[channel.status]}
    </span>
  );
}

/** The worst certificate state of a channel, for the card badge. */
function worstCertificate(certificates: ChannelCertificate[]) {
  return certificates
    .map((c) => certificateHealth(c))
    .filter((h) => h.state !== "ok")
    .sort((a, b) => a.days - b.days)[0];
}

function HealthBadge({ health, dict }: { health: ReturnType<typeof certificateHealth>; dict: Dictionary }) {
  const c = dict.channels.certificates;
  if (health.state === "expired") {
    return <span className={`${styles.badge} ${styles.badgeExpired}`}>{c.expired.replace("{days}", String(-health.days))}</span>;
  }
  if (health.state === "soon") {
    return <span className={`${styles.badge} ${styles.badgeSoon}`}>{c.soon.replace("{days}", String(health.days))}</span>;
  }
  return <span className={`${styles.badge} ${styles.badgeOk}`}>{c.ok}</span>;
}

function Head({ title, lead, note, children }: { title: string; lead: string; note: string; children?: ReactNode }) {
  return (
    <div className={portal.pageHead} style={{ marginBottom: 0, alignItems: "flex-end" }}>
      <div>
        <h1>{title}</h1>
        <p className={portal.muted} style={{ margin: "6px 0 0", maxWidth: "80ch" }}>
          {lead}
        </p>
        <p className={portal.declared} role="note" style={{ marginTop: 10, display: "inline-block" }}>
          {note}
        </p>
      </div>
      {children}
    </div>
  );
}

/** Every channel of the environment as cards, grouped by partner, with certificate alerts. */
export async function ChannelListScreen({ dict, companyId, base, crumbs, query }: Common) {
  const c = dict.channels;
  const [channels, partners, certificates] = await Promise.all([
    channelsApi.list(companyId),
    partnersOf(companyId),
    channelsApi.allCertificates(companyId),
  ]);
  if (channels.kind === "not-found") notFound();
  if (channels.kind !== "ok") return <Unreachable dict={dict} />;
  const certs = certificates.kind === "ok" ? certificates.data : [];
  const byChannel = new Map<string, ChannelCertificate[]>();
  certs.forEach((cert) => byChannel.set(cert.channelId, [...(byChannel.get(cert.channelId) ?? []), cert]));
  const partnerName = new Map(partners.map((p) => [p.id, p.name]));
  const groups = new Map<string, Channel[]>();
  for (const ch of channels.data) {
    const key = ch.partnerId && partnerName.has(ch.partnerId) ? ch.partnerId : "";
    groups.set(key, [...(groups.get(key) ?? []), ch]);
  }
  const ordered = [...groups.entries()].sort(([a], [b]) =>
    a === "" ? 1 : b === "" ? -1 : (partnerName.get(a) ?? "").localeCompare(partnerName.get(b) ?? ""),
  );
  const toRenew = certs.filter((cert) => certificateHealth(cert).state !== "ok").length;

  return (
    <div className={portal.stack}>
      <div>
        {crumbs}
        <Head title={c.title} lead={c.lead} note={c.transportNote}>
          <Link href={`${base}/new`} className={`${portal.btn} ${portal.btnPrimary}`}>
            {c.add}
          </Link>
        </Head>
      </div>
      <Notice dict={dict} query={query} />
      <div className={styles.stripGrid}>
        <div className={styles.stat}>
          <span>{c.strip.total}</span>
          <strong>{channels.data.length}</strong>
        </div>
        <div className={styles.stat}>
          <span>{c.strip.active}</span>
          <strong>{channels.data.filter((ch) => ch.status === "ACTIVE").length}</strong>
        </div>
        <div className={styles.stat}>
          <span>{c.strip.draft}</span>
          <strong>{channels.data.filter((ch) => ch.status === "DRAFT").length}</strong>
        </div>
        <div className={`${styles.stat} ${toRenew > 0 ? styles.statAlert : ""}`}>
          <span>{c.strip.certificates}</span>
          <strong>{toRenew}</strong>
        </div>
      </div>
      {channels.data.length === 0 ? (
        <section className={portal.panel}>
          <p className={portal.empty}>{c.empty}</p>
        </section>
      ) : (
        <>
          <p className={portal.muted} style={{ margin: 0, fontSize: 13 }}>
            {c.cardsLead}
          </p>
          {ordered.map(([partnerId, list]) => (
            <section key={partnerId || "shared"} className={styles.partnerGroup} aria-label={partnerName.get(partnerId) ?? c.sharedChannels}>
              <h2>{partnerId ? partnerName.get(partnerId) : c.sharedChannels}</h2>
              <ul className={styles.cards}>
                {list.map((ch) => {
                  const worst = worstCertificate(byChannel.get(ch.id) ?? []);
                  return (
                    <li key={ch.id}>
                      <Link href={`${base}/${ch.id}`} className={styles.card}>
                        <span className={styles.cardHead}>
                          <Glyph protocol={ch.protocol} />
                          <span className={styles.cardTitle}>
                            <b>{ch.name}</b>
                            <span dir="ltr">{channelEndpoint(ch)}</span>
                          </span>
                        </span>
                        <span className={styles.tags}>
                          <StatusTag channel={ch} dict={dict} />
                          <span className={styles.tag}>{c.directions[ch.direction]}</span>
                          <span className={styles.tag}>
                            {[ch.usedByMystix && "Mystix", ch.usedByAtelys && "Atelys"].filter(Boolean).join(" · ")}
                          </span>
                          {worst && <HealthBadge health={worst} dict={dict} />}
                        </span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </>
      )}
    </div>
  );
}

/** Create (no channelId) or edit a channel, in the three-step wizard. */
export async function ChannelEditScreen({
  lang,
  dict,
  companyId,
  app,
  base,
  crumbs,
  channelId,
}: Common & { channelId?: string }) {
  const c = dict.channels;
  const [catalog, partners, channel] = await Promise.all([
    channelsApi.catalog(companyId),
    partnersOf(companyId),
    channelId ? channelsApi.get(channelId, companyId) : Promise.resolve(null),
  ]);
  if (channel && channel.kind === "not-found") notFound();
  if (catalog.kind !== "ok" || (channel && channel.kind !== "ok")) return <Unreachable dict={dict} />;
  const existing = channel && channel.kind === "ok" ? channel.data : undefined;
  return (
    <div className={portal.stack}>
      <div>
        {crumbs}
        <Head title={existing ? `${c.edit} · ${existing.name}` : c.add} lead={c.lead} note={c.transportNote}>
          <Link href={existing ? `${base}/${existing.id}` : base} className={portal.btn}>
            {existing ? existing.name : c.back}
          </Link>
        </Head>
      </div>
      <section className={portal.panel} style={{ padding: 20 }}>
        <ChannelWizard
          lang={lang}
          labels={c}
          catalog={catalog.data}
          partners={partners}
          channel={existing}
          companyId={companyId}
          base={base}
          defaultApp={app}
        />
      </section>
    </div>
  );
}

async function companyName(companyId?: string) {
  const company = companyId ? await getCompany(companyId) : await getPortalCompany();
  return company && company.kind === "ok" ? company.data.legalName : "";
}

/** Text of the exchange sheet, for the preview and the download. */
export async function sheetText(lang: Locale, dict: Dictionary, channelId: string, companyId?: string) {
  const [channel, certificates, catalog, company] = await Promise.all([
    channelsApi.get(channelId, companyId),
    channelsApi.certificates(channelId, companyId),
    channelsApi.catalog(companyId),
    companyName(companyId),
  ]);
  if (channel.kind !== "ok") return null;
  return exchangeSheet({
    labels: dict.channels,
    company,
    channel: channel.data,
    certificates: certificates.kind === "ok" ? certificates.data : [],
    as2InboundUrl: catalog.kind === "ok" ? catalog.data.as2InboundUrl : null,
    generatedAt: formatDateTime(lang, new Date().toISOString()),
  });
}

/** The channel sheet: what it is, its configuration, its certificates, the exchange sheet for the partner. */
export async function ChannelDetailScreen({
  lang,
  dict,
  companyId,
  base,
  crumbs,
  query,
  channelId,
}: Common & { channelId: string }) {
  const c = dict.channels;
  const [channel, certificates, catalog, partners, sheet] = await Promise.all([
    channelsApi.get(channelId, companyId),
    channelsApi.certificates(channelId, companyId),
    channelsApi.catalog(companyId),
    partnersOf(companyId),
    sheetText(lang, dict, channelId, companyId),
  ]);
  if (channel.kind === "not-found") notFound();
  if (channel.kind !== "ok" || catalog.kind !== "ok") return <Unreachable dict={dict} />;
  const ch = channel.data;
  const certs = certificates.kind === "ok" ? certificates.data : [];
  const spec = catalog.data.protocols.find((p) => p.code === ch.protocol);
  const partner = partners.find((p) => p.id === ch.partnerId)?.name;
  const page = `${base}/${ch.id}`;
  const field = (key: string) =>
    ch.protocol === "AS2" && key === "url" ? c.fields.as2Url : c.fields[key as keyof typeof c.fields] ?? key;
  const hidden = (
    <>
      <input type="hidden" name="lang" value={lang} />
      <input type="hidden" name="_base" value={base} />
      <input type="hidden" name="_back" value={page} />
      {companyId && <input type="hidden" name="companyId" value={companyId} />}
      <input type="hidden" name="id" value={ch.id} />
    </>
  );

  return (
    <div className={portal.stack}>
      <div>
        {crumbs}
        <div className={styles.hero}>
          <Glyph protocol={ch.protocol} large />
          <div className={styles.heroText}>
            <h1>{ch.name}</h1>
            <span className={styles.tags}>
              <StatusTag channel={ch} dict={dict} />
              <span className={styles.tag}>{c.directions[ch.direction]}</span>
              <span className={styles.tag}>{partner ?? c.form.noPartner}</span>
              <span className={styles.tag}>
                {[ch.usedByMystix && "Mystix", ch.usedByAtelys && "Atelys"].filter(Boolean).join(" · ")}
              </span>
              <span className={`${styles.tag} ${styles.mono}`} dir="ltr">
                {channelEndpoint(ch)}
              </span>
            </span>
          </div>
          <Link href={`${page}/edit`} className={`${portal.btn} ${portal.btnPrimary}`}>
            {c.editAction}
          </Link>
        </div>
        <p className={portal.declared} role="note" style={{ marginTop: 14, display: "inline-block" }}>
          {c.transportNote}
        </p>
      </div>
      <Notice dict={dict} query={query} />

      <section className={portal.panel} aria-labelledby="configuration">
        <div className={portal.panelHead}>
          <h2 id="configuration">{c.configuration}</h2>
          <span className={portal.hint}>{c.protocolHints[ch.protocol]}</span>
        </div>
        <div className={styles.sectionBody}>
          {(["CONNECTION", "FILES", "SECURITY", "ADVANCED"] as const).map((g) => {
            const fields = (spec?.fields ?? []).filter((f) => f.group === g);
            if (fields.length === 0) return null;
            return (
              <div key={g} className={styles.group}>
                <h3 className={styles.groupTitle} style={{ margin: 0 }}>
                  {c.groups[g]}
                </h3>
                <dl className={styles.kv}>
                  {fields.map((f) => {
                    const v = ch.settings[f.key];
                    const shown = v === undefined ? null : typeof v === "boolean" ? (v ? c.form.yes : c.form.no) : String(v);
                    const isDefault = v !== undefined && f.defaultValue !== null && String(v) === String(f.defaultValue);
                    return (
                      <div key={f.key}>
                        <dt>{field(f.key)}</dt>
                        <dd className={f.kind === "TEXT" || f.kind === "CHOICE" || f.kind === "FLAG" ? undefined : styles.mono} dir="auto">
                          {shown ?? <span className={portal.muted}>{c.notSet}</span>}
                          {isDefault && <small> · {c.defaultMark}</small>}
                        </dd>
                      </div>
                    );
                  })}
                  {g === "SECURITY" && (
                    <div>
                      <dt>{c.form.credentialRef}</dt>
                      <dd className={styles.mono} dir="ltr">
                        {ch.credentialRef ?? <span className={portal.muted}>{c.notSet}</span>}
                      </dd>
                    </div>
                  )}
                </dl>
              </div>
            );
          })}
        </div>
      </section>

      <section className={portal.panel} aria-labelledby="certificates" id="certificates">
        <div className={portal.panelHead}>
          <h2 id="certificates">{c.certificates.title}</h2>
          <span className={portal.hint}>{c.certificates.lead}</span>
        </div>
        <div className={styles.sectionBody}>
          {certs.length === 0 ? (
            <p className={portal.muted} style={{ margin: 0 }}>
              {c.certificates.none}
            </p>
          ) : (
            <ul className={styles.certList}>
              {certs.map((cert) => {
                const health = certificateHealth(cert);
                return (
                  <li key={cert.id} className={styles.cert} data-health={health.state}>
                    <div className={styles.certHead}>
                      <b>{c.certificates.roles[cert.role]}</b>
                      <HealthBadge health={health} dict={dict} />
                      <span className={portal.muted} style={{ fontSize: 12.5 }}>
                        {cert.keyAlgorithm}
                        {cert.keySize ? ` ${cert.keySize}` : ""} ·{" "}
                        {c.certificates.validUntil.replace("{date}", formatDateTime(lang, cert.notAfter))}
                      </span>
                    </div>
                    <form action={deleteCertificateAction}>
                      {hidden}
                      <input type="hidden" name="certificateId" value={cert.id} />
                      <ConfirmButton className={`${portal.btn} ${portal.btnSm}`} message={c.certificates.removeConfirm}>
                        {c.certificates.remove}
                      </ConfirmButton>
                    </form>
                    <div className={styles.certMeta}>
                      <span dir="ltr">{cert.subject}</span>
                      <span>
                        {c.certificates.issuer} : <span dir="ltr">{cert.issuer}</span>
                      </span>
                      <span>
                        {c.certificates.fingerprint} : <span className={styles.fingerprint} dir="ltr">{cert.sha256Fingerprint}</span>
                      </span>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          <form action={addCertificateAction} className={styles.upload}>
            {hidden}
            <b style={{ fontSize: 14 }}>{c.certificates.add}</b>
            <div className={styles.fields}>
              <label className={styles.field}>
                <span>{c.certificates.role}</span>
                <select name="role" defaultValue="PARTNER">
                  <option value="PARTNER">{c.certificates.roles.PARTNER}</option>
                  <option value="OWN">{c.certificates.roles.OWN}</option>
                </select>
              </label>
              <label className={styles.field}>
                <span>{c.certificates.file}</span>
                <input type="file" name="file" accept=".pem,.crt,.cer,.der" />
              </label>
            </div>
            <label className={styles.field}>
              <span>{c.certificates.paste}</span>
              <textarea name="pem" placeholder="-----BEGIN CERTIFICATE-----" dir="ltr" spellCheck={false} />
            </label>
            <div>
              <button type="submit" className={portal.btn}>
                {c.certificates.add}
              </button>
            </div>
          </form>
        </div>
      </section>

      <section className={portal.panel} aria-labelledby="sheet">
        <div className={portal.panelHead}>
          <h2 id="sheet">{c.sheet.title}</h2>
          <span className={portal.hint}>{c.sheet.lead}</span>
          <div className={portal.right}>
            <a href={`${page}/sheet`} className={`${portal.btn} ${portal.btnPrimary}`} download>
              {c.sheet.download}
            </a>
          </div>
        </div>
        <div className={styles.sectionBody}>
          <pre className={styles.sheetPreview} dir="auto">
            {sheet}
          </pre>
        </div>
      </section>

      <div className={styles.danger}>
        <div>
          <b>{c.dangerTitle}</b>
          <p>{c.dangerLead}</p>
        </div>
        <form action={deleteChannelAction}>
          {hidden}
          <ConfirmButton className={`${portal.btn} ${portal.btnDanger}`} message={c.form.deleteConfirm}>
            {c.form.delete}
          </ConfirmButton>
        </form>
      </div>
    </div>
  );
}
