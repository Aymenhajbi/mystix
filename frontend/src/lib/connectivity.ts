import { apiBaseUrl, getForCompany } from "./api";

/** Connectivity Manager (ADR-0016): entry points into the pipeline and the listeners that feed them. */

export type ListenerKind = "HTTP_INBOUND" | "SFTP_POLLING" | "AS2_SERVER";
export type DesiredState = "RUNNING" | "STOPPED";
export type RuntimeState =
  | "CONFIGURED"
  | "STARTING"
  | "RUNNING"
  | "DEGRADED"
  | "STOPPING"
  | "STOPPED"
  | "ERROR"
  | "DISABLED";

export type EntryPoint = {
  id: string;
  code: string;
  name: string;
  flowId: string;
  documentType: "INVOICE";
  inputFormat: "JSON" | "XML" | "EDIFACT" | "CSV";
  partnerId: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
};

export type ListenerView = {
  id: string;
  name: string;
  kind: ListenerKind;
  channelId: string;
  entryPointId: string;
  settings: Record<string, unknown>;
  enabled: boolean;
  desiredState: DesiredState;
  /** Observed state: a RUNNING listener without a recent heartbeat reads DEGRADED. */
  state: RuntimeState;
  recordedState: RuntimeState;
  runtimeInstance: string | null;
  lastHeartbeatAt: string | null;
  lastError: { code?: string; detail?: string } | null;
  startedAt: string | null;
  stoppedAt: string | null;
  lastActivityAt: string | null;
  receivedCount: number;
  rejectedCount: number;
  apiKeyHint: string | null;
  endpoint: string | null;
  createdAt: string;
  updatedAt: string;
  /** Only in the answer to a creation or a key rotation. */
  apiKey: string | null;
};

export type ListenerEvent = {
  id: string;
  eventType: string;
  severity: "INFO" | "WARN" | "ERROR";
  correlationId: string | null;
  details: Record<string, unknown>;
  actor: string | null;
  createdAt: string;
};

export const connectivityApi = {
  entryPoints: () => getForCompany<EntryPoint[]>("/api/v1/connectivity/entry-points"),
  listeners: () => getForCompany<ListenerView[]>("/api/v1/connectivity/listeners"),
  listener: (id: string) => getForCompany<ListenerView>(`/api/v1/connectivity/listeners/${encodeURIComponent(id)}`),
  events: (id: string, limit = 100) =>
    getForCompany<ListenerEvent[]>(`/api/v1/connectivity/listeners/${encodeURIComponent(id)}/events?limit=${limit}`),
};

/** Where partners send: the backend address followed by the listener path. */
export function receptionUrl(endpoint: string | null): string | null {
  return endpoint ? `${apiBaseUrl.replace(/\/$/, "")}${endpoint}` : null;
}

/** Severity class of a state, for the badge colour; the text always says the state. */
export function stateTone(state: RuntimeState): "ok" | "warn" | "err" | "info" {
  if (state === "RUNNING") return "ok";
  if (state === "ERROR") return "err";
  if (state === "DEGRADED" || state === "STARTING" || state === "STOPPING") return "warn";
  return "info";
}
