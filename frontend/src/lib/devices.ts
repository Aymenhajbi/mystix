import { getForCompany } from "./api";

/** Atelys Device Hub (ADR-0019, lot D): terminals, selection profiles and scan tests. */

export type DeviceHealth = "OK" | "ACTION_REQUIRED" | "ERROR" | "OFFLINE";

export type ResolutionState =
  | "AUTO_SELECTED"
  | "USER_SELECTION_REQUIRED"
  | "CONFIGURATION_REQUIRED"
  | "DEVICE_UNSUPPORTED"
  | "SCANNER_UNAVAILABLE"
  | "DIAGNOSTIC_REQUIRED";

export type Device = {
  id: string;
  installationId: string;
  label: string | null;
  manufacturer: string;
  brand: string | null;
  model: string;
  product: string | null;
  androidRelease: string | null;
  sdkInt: number;
  buildId: string | null;
  appVersion: string | null;
  capabilities: string[];
  resolutionState: ResolutionState | null;
  recommendedAdapter: string | null;
  resolutionReasons: string[];
  activeAdapter: string | null;
  validatedAt: string | null;
  preferredAdapter: string | null;
  preferredBy: string | null;
  preferredAt: string | null;
  resetRequestedAt: string | null;
  testRequestedAt: string | null;
  lastErrorCode: string | null;
  lastErrorAt: string | null;
  lastSeenAt: string;
  lastSeenBy: string | null;
  createdAt: string;
  health: DeviceHealth;
};

export type Diagnostic = {
  id: string;
  adapterId: string;
  outcome: "PASSED" | "MISMATCH" | "TIMEOUT" | "OPEN_FAILED" | "ADAPTER_ERROR" | "CANCELLED";
  simulated: boolean;
  symbology: string | null;
  dataLength: number | null;
  errorCode: string | null;
  startedAt: string;
  finishedAt: string;
  receivedAt: string;
};

export type ProfileStatus = "VALIDATED" | "EXPERIMENTAL" | "RETIRED";

export type DeviceProfile = {
  id: string;
  name: string;
  version: number;
  scope: "INSTALLATION" | "MODEL" | "MANUFACTURER";
  manufacturer: string;
  models: string[];
  installationId: string | null;
  minSdk: number | null;
  maxSdk: number | null;
  recommendedAdapter: string;
  prerequisites: string | null;
  confidence: "LOW" | "MEDIUM" | "HIGH";
  status: ProfileStatus;
  testProcedure: string | null;
  updatedAt: string;
  updatedBy: string | null;
};

export type ScanAdapter = { id: string; name: string; honeywellOnly: boolean; experimental: boolean };

export type AuditEntry = {
  id: string;
  action: string;
  actor: string | null;
  reason: string | null;
  detail: string | null;
  at: string;
};

export type DeviceView = {
  device: Device;
  diagnostics: Diagnostic[];
  audit: AuditEntry[];
  profiles: DeviceProfile[];
};

export const devices = {
  list: () => getForCompany<Device[]>("/api/v1/atelys/devices"),
  get: (id: string) => getForCompany<DeviceView>(`/api/v1/atelys/devices/${encodeURIComponent(id)}`),
  profiles: () => getForCompany<DeviceProfile[]>("/api/v1/atelys/device-profiles"),
  adapters: () => getForCompany<ScanAdapter[]>("/api/v1/atelys/device-adapters"),
};
