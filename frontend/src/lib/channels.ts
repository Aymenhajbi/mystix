import { getForCompany } from "./api";

/** Communication channels (ADR-0015): one configuration shared by Mystix and Atelys. */

export const CHANNEL_PROTOCOLS = ["AS2", "SFTP", "FTP", "FTPS", "HTTPS", "WEBHOOK"] as const;
export type ChannelProtocol = (typeof CHANNEL_PROTOCOLS)[number];
export type ChannelDirection = "IN" | "OUT" | "BOTH";
export type ChannelStatus = "DRAFT" | "ACTIVE" | "DISABLED";
export type ChannelApp = "MYSTIX" | "ATELYS";

export type Channel = {
  id: string;
  companyId: string;
  name: string;
  protocol: ChannelProtocol;
  direction: ChannelDirection;
  partnerId: string | null;
  usedByMystix: boolean;
  usedByAtelys: boolean;
  status: ChannelStatus;
  settings: Record<string, string | number | boolean>;
  credentialRef: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type ChannelFieldKind =
  | "TEXT"
  | "HOST"
  | "PORT"
  | "SECONDS"
  | "FLAG"
  | "URL"
  | "HTTPS_URL"
  | "AS2_ID"
  | "PATH"
  | "CHOICE";

export type ChannelFieldGroup = "CONNECTION" | "FILES" | "SECURITY" | "ADVANCED";

export type ChannelField = {
  key: string;
  kind: ChannelFieldKind;
  group: ChannelFieldGroup;
  required: boolean;
  defaultValue: string | number | boolean | null;
  choices: string[];
};

export type ChannelCatalog = {
  protocols: { code: ChannelProtocol; fields: ChannelField[]; needsCredential: boolean }[];
  directions: ChannelDirection[];
  statuses: ChannelStatus[];
  transport: string;
  /** Where partners send AS2 to Mystix; null until the AS2 transport is deployed. */
  as2InboundUrl: string | null;
};

export type CertificateRole = "OWN" | "PARTNER";

export type ChannelCertificate = {
  id: string;
  channelId: string;
  role: CertificateRole;
  subject: string;
  issuer: string;
  serialNumber: string;
  keyAlgorithm: string;
  keySize: number | null;
  notBefore: string;
  notAfter: string;
  sha256Fingerprint: string;
  pem: string;
  createdBy: string;
  createdAt: string;
};

/** Days before expiry when a certificate starts to be flagged. */
export const CERTIFICATE_WARNING_DAYS = 30;

export type CertificateHealth = { state: "ok" | "soon" | "expired"; days: number };

export function certificateHealth(c: ChannelCertificate, now = Date.now()): CertificateHealth {
  const days = Math.floor((new Date(c.notAfter).getTime() - now) / 86_400_000);
  return { state: days < 0 ? "expired" : days <= CERTIFICATE_WARNING_DAYS ? "soon" : "ok", days };
}

/** With companyId, the environment chosen in Mystix; without, the active environment of the session (Atelys). */
export const channelsApi = {
  catalog: (companyId?: string) => getForCompany<ChannelCatalog>("/api/v1/channels/catalog", companyId),
  list: (companyId?: string, app?: ChannelApp) =>
    getForCompany<Channel[]>(`/api/v1/channels${app ? `?app=${app}` : ""}`, companyId),
  get: (id: string, companyId?: string) =>
    getForCompany<Channel>(`/api/v1/channels/${encodeURIComponent(id)}`, companyId),
  certificates: (id: string, companyId?: string) =>
    getForCompany<ChannelCertificate[]>(`/api/v1/channels/${encodeURIComponent(id)}/certificates`, companyId),
  allCertificates: (companyId?: string) =>
    getForCompany<ChannelCertificate[]>("/api/v1/channels/certificates", companyId),
};

/** The address shown in lists: host and port, or the URL. */
export function channelEndpoint(c: Channel): string {
  const s = c.settings;
  if (typeof s.url === "string") return s.url;
  if (typeof s.host === "string") return `${s.host}${s.port ? `:${s.port}` : ""}`;
  if (c.protocol === "AS2" && typeof s.partnerAs2Id === "string") return `AS2-To ${s.partnerAs2Id}`;
  return "—";
}
