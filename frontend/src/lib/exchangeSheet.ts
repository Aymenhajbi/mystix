import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { Channel, ChannelCertificate } from "./channels";

type Labels = Dictionary["channels"];

/**
 * The exchange sheet of a channel (ADR-0015): what the partner needs to configure its side, as plain text that any
 * integrator can read, paste into a ticket or attach to an e-mail. Public data only: identifiers, addresses,
 * parameters, public certificates. Never a secret, never the credential reference.
 */
export function exchangeSheet({
  labels,
  company,
  channel,
  certificates,
  as2InboundUrl,
  generatedAt,
}: {
  labels: Labels;
  company: string;
  channel: Channel;
  certificates: ChannelCertificate[];
  as2InboundUrl: string | null;
  generatedAt: string;
}): string {
  const s = labels.sheet;
  const field = (key: string) => labels.fields[key as keyof Labels["fields"]] ?? key;
  const value = (key: string) => {
    const v = channel.settings[key];
    if (v === undefined || v === null || v === "") return null;
    if (typeof v === "boolean") return v ? labels.form.yes : labels.form.no;
    return String(v);
  };
  const line = (label: string, v: string | null) => `  ${label} : ${v ?? labels.notSet}`;
  const own = certificates.filter((c) => c.role === "OWN");
  const theirs = certificates.filter((c) => c.role === "PARTNER");
  const cert = (c: ChannelCertificate) => [
    `    ${c.subject}`,
    `    ${labels.certificates.fingerprint} : ${c.sha256Fingerprint}`,
    `    ${labels.certificates.validity} : ${c.notBefore.slice(0, 10)} → ${c.notAfter.slice(0, 10)}`,
  ];
  const out: string[] = [];
  const rule = "=".repeat(72);
  out.push(rule, `${s.title.toUpperCase()} · ${channel.protocol}`, rule);
  out.push(line(s.company, company), line(s.channel, channel.name), line(labels.form.direction, labels.directions[channel.direction]), "");

  if (channel.protocol === "AS2") {
    out.push(s.ours);
    out.push(line(s.as2Id, value("localAs2Id")));
    out.push(line(s.inboundUrl, as2InboundUrl ?? s.toConfirm));
    out.push(`  ${s.certificate} :`);
    if (own.length === 0) out.push(`    ${s.noOwnCertificate}`);
    own.forEach((c) => out.push(...cert(c)));
    out.push("", s.theirs);
    out.push(line(s.as2Id, value("partnerAs2Id")));
    out.push(line(field("url"), value("url")));
    out.push(`  ${s.certificate} :`);
    if (theirs.length === 0) out.push(`    ${s.noPartnerCertificate}`);
    theirs.forEach((c) => out.push(...cert(c)));
    out.push("", s.parameters);
    for (const key of ["signing", "encryption", "compression", "mdn", "mdnSigned", "asyncMdnUrl"]) {
      if (key === "asyncMdnUrl" && channel.settings.mdn !== "ASYNC") continue;
      out.push(line(field(key), value(key)));
    }
  } else if (channel.protocol === "SFTP" || channel.protocol === "FTP" || channel.protocol === "FTPS") {
    out.push(s.theirs);
    out.push(line(s.server, `${value("host") ?? labels.notSet}:${value("port") ?? ""}`));
    out.push(line(s.account, value("username")));
    if (channel.protocol === "SFTP") {
      out.push(line(field("authMethod"), value("authMethod")));
      out.push(line(s.expectedHostKey, value("hostKeyFingerprint")));
    }
    if (channel.protocol === "FTPS") out.push(line(field("tlsMode"), value("tlsMode")));
    out.push("", s.parameters);
    for (const key of ["inboundDirectory", "outboundDirectory", "filePattern", "pollSeconds", "afterDownload", "transferMode", "passiveMode"]) {
      if (value(key) !== null) out.push(line(field(key), value(key)));
    }
  } else {
    out.push(s.theirs);
    out.push(line(field("url"), value("url")));
    for (const key of ["method", "authType", "contentType", "events"]) {
      if (value(key) !== null) out.push(line(field(key), value(key)));
    }
  }

  // Our public certificate, ready to import on the partner's side.
  for (const c of own) out.push("", `${labels.certificates.roles.OWN} (PEM)`, c.pem.trim());
  out.push("", rule, s.generated.replace("{date}", generatedAt), "");
  return out.join("\n");
}
