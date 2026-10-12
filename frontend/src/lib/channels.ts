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

export type ChannelField = {
  key: string;
  kind: ChannelFieldKind;
  required: boolean;
  defaultValue: string | number | boolean | null;
  choices: string[];
};

export type ChannelCatalog = {
  protocols: { code: ChannelProtocol; fields: ChannelField[]; needsCredential: boolean }[];
  directions: ChannelDirection[];
  statuses: ChannelStatus[];
  transport: string;
};

/** With companyId, the environment chosen in Mystix; without, the active environment of the session (Atelys). */
export const channelsApi = {
  catalog: (companyId?: string) => getForCompany<ChannelCatalog>("/api/v1/channels/catalog", companyId),
  list: (companyId?: string, app?: ChannelApp) =>
    getForCompany<Channel[]>(`/api/v1/channels${app ? `?app=${app}` : ""}`, companyId),
  get: (id: string, companyId?: string) =>
    getForCompany<Channel>(`/api/v1/channels/${encodeURIComponent(id)}`, companyId),
};

/** The address shown in lists: host and port, or the URL. */
export function channelEndpoint(c: Channel): string {
  const s = c.settings;
  if (typeof s.url === "string") return s.url;
  if (typeof s.host === "string") return `${s.host}${s.port ? `:${s.port}` : ""}`;
  if (c.protocol === "AS2" && typeof s.partnerAs2Id === "string") return `AS2-To ${s.partnerAs2Id}`;
  return "—";
}
