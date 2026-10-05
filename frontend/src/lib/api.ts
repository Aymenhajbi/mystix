/**
 * Server-side client of the Mystix backend. Only imported by Server Components and Route Handlers:
 * the company identifier never reaches the browser.
 *
 * TODO(auth): the active client environment comes from the "mystix-company" cookie set by the environment picker,
 * or MYSTIX_PORTAL_COMPANY_ID, until authentication exists (Lot 8).
 */
import { cookies } from "next/headers";

export const COMPANY_COOKIE = "mystix-company";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (v: string | undefined | null): v is string => !!v && UUID.test(v);
export const apiBaseUrl =
  process.env.MYSTIX_API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/** Active client environment: the picker's cookie, else the configured default. */
export async function portalCompanyId(): Promise<string | null> {
  const fromCookie = (await cookies()).get(COMPANY_COOKIE)?.value;
  if (isUuid(fromCookie)) return fromCookie;
  const configured = process.env.MYSTIX_PORTAL_COMPANY_ID?.trim();
  return isUuid(configured) ? configured : null;
}

export type InvoiceStatus = "VALIDATED" | "CLEARED" | "CLEARANCE_REJECTED";

export type Clearance = { reference: string | null; simulated: boolean | null; at: string | null };

export type InvoiceSummary = {
  id: string;
  number: string;
  issueDate: string;
  buyerName: string | null;
  currency: string | null;
  payableAmount: string | null;
  status: InvoiceStatus;
  clearance: Clearance;
  createdAt: string;
};

export type InvoiceDetail = Omit<InvoiceSummary, "clearance"> & {
  canonicalVersion: string;
  clearance: Clearance;
  artifacts: { kind: "RAW" | "CANONICAL" | "OUT"; mediaType: string; sha256: string; size: number }[];
  history: { status: string; detail: string | null; occurredAt: string }[];
};

export type BackendHealth = { status: "UP" | "DOWN" | "UNREACHABLE" };

export type ApiResult<T> =
  | { kind: "ok"; data: T }
  | { kind: "no-company" }
  | { kind: "not-found" }
  | { kind: "unreachable" };

export async function fetchBackendHealth(): Promise<BackendHealth> {
  try {
    const response = await fetch(`${apiBaseUrl}/actuator/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    const body = (await response.json()) as { status?: string };
    return { status: body.status === "UP" ? "UP" : "DOWN" };
  } catch {
    return { status: "UNREACHABLE" };
  }
}

/** Upper bound of invoices the cockpit aggregates; statistics say so when it is reached. */
export const INVOICE_WINDOW = 200;

export const listInvoices = () => companyGet<InvoiceSummary[]>(`/api/v1/invoices?limit=${INVOICE_WINDOW}`);

export type Company = { id: string; ice: string; legalName: string; taxIdentifier: string | null };

export const getPortalCompany = async () => {
  const company = await portalCompanyId();
  return company ? companyGet<Company>(`/api/v1/companies/${encodeURIComponent(company)}`) : null;
};

export type LogLevel = "INFO" | "WARN" | "ERROR";

export type LogEvent =
  | "INVOICE_ACCEPTED"
  | "INVOICE_REPLAYED"
  | "INVOICE_REJECTED"
  | "CLEARANCE_CLEARED"
  | "CLEARANCE_REJECTED"
  | "CLEARANCE_ERROR";

export type LogEntry = {
  id: string;
  seq: number;
  occurredAt: string;
  level: LogLevel;
  stage: string;
  event: LogEvent;
  errorCode: string | null;
  userMessage: { fr: string; ar: string } | null;
  suggestedAction: { fr: string; ar: string } | null;
  invoiceNumber: string | null;
  invoiceId: string | null;
  requestId: string | null;
  message: string;
  details: {
    fieldErrors?: { field: string; reason: string }[];
    ruleViolations?: { ruleId: string; severity: string; location: string; message: string }[];
  } | null;
  payloadSize: number | null;
};

export type LogStat = {
  event: LogEvent;
  stage: string;
  errorCode: string | null;
  count: number;
  lastAt: string;
};

export type LogStats = { since: string; until: string; stats: LogStat[] };

/** Aggregates of the processing log over an ISO 8601 window (PT1H, PT24H, P7D). */
export const getLogStats = (window: string) =>
  companyGet<LogStats>(`/api/v1/logs/stats?window=${encodeURIComponent(window)}`);

export type MappingKind = "DIRECT" | "CONSTANT" | "CALCULATED" | "CONFIGURED" | "NOT_EMITTED";

export type LineageValue = { path: string; value: string | null };

export type LineageRow = {
  group: "header" | "seller" | "buyer" | "line" | "totals";
  line: number | null;
  term: string | null;
  label: string;
  kind: MappingKind;
  rule: string;
  request: LineageValue | null;
  canonical: LineageValue | null;
  target: LineageValue | null;
};

export type Lineage = { mapping: string; mappingVersion: string; rows: LineageRow[] };

export const getLineage = (invoiceId: string) =>
  companyGet<Lineage>(`/api/v1/invoices/${encodeURIComponent(invoiceId)}/lineage`);

/** Upper bound of log lines fetched at once. */
export const LOG_WINDOW = 500;

export const listLogs = (options: { level?: LogLevel; invoiceId?: string; limit?: number } = {}) => {
  const p = new URLSearchParams({ level: options.level ?? "INFO", limit: String(options.limit ?? LOG_WINDOW) });
  if (options.invoiceId) p.set("invoiceId", options.invoiceId);
  return companyGet<LogEntry[]>(`/api/v1/logs?${p}`);
};

/** Request body kept with a rejected submission, for the payload route. */
export async function fetchLogPayload(id: string): Promise<ApiResult<ArrayBuffer>> {
  const company = await portalCompanyId();
  if (!company) return { kind: "no-company" };
  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/logs/${encodeURIComponent(id)}/payload`, {
      cache: "no-store",
      headers: { "X-Mystix-Company-Id": company },
      signal: AbortSignal.timeout(5000),
    });
    if (response.status === 404 || response.status === 400) return { kind: "not-found" };
    if (!response.ok) return { kind: "unreachable" };
    return { kind: "ok", data: await response.arrayBuffer() };
  } catch {
    return { kind: "unreachable" };
  }
}

export type ArtifactKind = "RAW" | "CANONICAL" | "OUT";

/** Stored artefact as text (RAW and CANONICAL are JSON, OUT is UBL XML). */
export async function fetchArtifactText(id: string, kind: ArtifactKind): Promise<ApiResult<string>> {
  const company = await portalCompanyId();
  if (!company) return { kind: "no-company" };
  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/invoices/${encodeURIComponent(id)}/artifacts/${kind}`, {
      cache: "no-store",
      headers: { "X-Mystix-Company-Id": company },
      signal: AbortSignal.timeout(5000),
    });
    if (response.status === 404 || response.status === 400) return { kind: "not-found" };
    if (!response.ok) return { kind: "unreachable" };
    return { kind: "ok", data: new TextDecoder("utf-8").decode(await response.arrayBuffer()) };
  } catch {
    return { kind: "unreachable" };
  }
}

export const getInvoice = (id: string) => companyGet<InvoiceDetail>(`/api/v1/invoices/${encodeURIComponent(id)}`);

/** Raw UBL of an invoice, for the download route. */
export async function fetchInvoiceUbl(id: string): Promise<ApiResult<ArrayBuffer>> {
  const company = await portalCompanyId();
  if (!company) return { kind: "no-company" };
  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/invoices/${encodeURIComponent(id)}/artifacts/OUT`, {
      cache: "no-store",
      headers: { "X-Mystix-Company-Id": company },
      signal: AbortSignal.timeout(5000),
    });
    if (response.status === 404 || response.status === 400) return { kind: "not-found" };
    if (!response.ok) return { kind: "unreachable" };
    return { kind: "ok", data: await response.arrayBuffer() };
  } catch {
    return { kind: "unreachable" };
  }
}

async function companyGet<T>(path: string, forCompany?: string): Promise<ApiResult<T>> {
  const company = forCompany ?? (await portalCompanyId());
  if (!company) return { kind: "no-company" };
  try {
    const response = await fetch(`${apiBaseUrl}${path}`, {
      cache: "no-store",
      headers: { "X-Mystix-Company-Id": company, Accept: "application/json" },
      signal: AbortSignal.timeout(5000),
    });
    // 400 covers a malformed id in the URL; 404 an unknown invoice or company.
    if (response.status === 404 || response.status === 400) return { kind: "not-found" };
    if (!response.ok) return { kind: "unreachable" };
    return { kind: "ok", data: (await response.json()) as T };
  } catch {
    return { kind: "unreachable" };
  }
}

// ---------- client environments and flows (ADR-0007) ----------

export type Environment = {
  id: string;
  ice: string;
  legalName: string;
  createdAt: string;
  flows: number;
  activeFlows: number;
  invoices: number;
  errors24h: number;
  lastActivity: string | null;
};

/** Operator list of client environments; "not-found" when the operator console is disabled. */
export async function listEnvironments(): Promise<ApiResult<Environment[]>> {
  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/admin/environments`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(5000),
    });
    if (response.status === 404) return { kind: "not-found" };
    if (!response.ok) return { kind: "unreachable" };
    return { kind: "ok", data: (await response.json()) as Environment[] };
  } catch {
    return { kind: "unreachable" };
  }
}

export const getCompany = (companyId: string) =>
  companyGet<Company>(`/api/v1/companies/${encodeURIComponent(companyId)}`, companyId);

export type FlowStatus = "DRAFT" | "ACTIVE" | "PAUSED";

export type ExchangeFlow = {
  id: string;
  companyId: string;
  name: string;
  documentType: string;
  direction: string;
  sourceChannel: string;
  sourceFormat: string;
  targetFormat: string;
  targetChannel: string;
  mappingId: string;
  mappingVersion: string;
  status: FlowStatus;
  createdAt: string;
  updatedAt: string;
};

export type CatalogOption = { code: string; available: boolean; lot: string | null };

export type FlowCatalog = {
  sourceChannels: CatalogOption[];
  sourceFormats: CatalogOption[];
  targetFormats: CatalogOption[];
  targetChannels: CatalogOption[];
  mappings: { sourceFormat: string; targetFormat: string; id: string; version: string }[];
};

export const listFlows = (companyId: string) => companyGet<ExchangeFlow[]>("/api/v1/flows", companyId);

export const getFlow = (companyId: string, flowId: string) =>
  companyGet<ExchangeFlow>(`/api/v1/flows/${encodeURIComponent(flowId)}`, companyId);

export const getFlowCatalog = (companyId: string) => companyGet<FlowCatalog>("/api/v1/flows/catalog", companyId);

export const listFlowInvoices = (companyId: string, flowId: string, limit = 20) =>
  companyGet<InvoiceSummary[]>(`/api/v1/invoices?flowId=${encodeURIComponent(flowId)}&limit=${limit}`, companyId);

export const getLineageFor = (companyId: string, invoiceId: string) =>
  companyGet<Lineage>(`/api/v1/invoices/${encodeURIComponent(invoiceId)}/lineage`, companyId);

export type SpecField = {
  group: LineageRow["group"];
  term: string | null;
  label: string;
  request: string | null;
  canonical: string | null;
  target: string | null;
  kind: MappingKind;
  rule: string;
};

export type MappingSpec = { id: string; version: string; fields: SpecField[] };

export const getMappingSpec = (companyId: string) => companyGet<MappingSpec>("/api/v1/mappings/ubl-invoice", companyId);

/** Lineage rows without values, for a flow that has not received an invoice yet (one line shown). */
export function specRows(spec: MappingSpec): LineageRow[] {
  const fill = (p: string | null) => (p === null ? null : { path: p.replace("{i}", "0").replace("{n}", "1"), value: null });
  return spec.fields.map((f) => ({
    group: f.group,
    line: f.group === "line" ? 0 : null,
    term: f.term,
    label: f.label,
    kind: f.kind,
    rule: f.rule,
    request: fill(f.request),
    canonical: fill(f.canonical),
    target: fill(f.target),
  }));
}

export type MutationResult<T> = { ok: true; data: T } | { ok: false; errorCode: string };

/** Writes for the flow screens (called from server actions only). */
export async function sendFlowMutation<T>(
  companyId: string,
  method: "POST" | "PATCH",
  path: string,
  body: unknown,
): Promise<MutationResult<T>> {
  try {
    const response = await fetch(`${apiBaseUrl}${path}`, {
      method,
      cache: "no-store",
      headers: { "X-Mystix-Company-Id": companyId, "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
    const json = (await response.json().catch(() => null)) as (T & { errorCode?: string }) | null;
    if (!response.ok) return { ok: false, errorCode: json?.errorCode ?? "INTERNAL_ERROR" };
    return { ok: true, data: json as T };
  } catch {
    return { ok: false, errorCode: "API_UNREACHABLE" };
  }
}

// ---------- mapping versions (ADR-0008) ----------

export type RuleTransform = { op: string; value?: string | null; table?: Record<string, string>; fallback?: string | null };
export type RuleSource = { type: "FIELD" | "CONSTANT"; path?: string | null; value?: string | null };
export type MappingRuleDto = { target: string; source: RuleSource | null; transforms: RuleTransform[] };
export type RuleField = { path: string; scope: "HEADER" | "LINE"; term: string; maxLen: number };

export type TestSample = {
  label: string;
  passed: boolean;
  errors: string[];
  changes: { term: string | null; label: string; line: number | null; before: string | null; after: string | null }[];
};

export type MappingVersionDto = {
  id: string;
  version: number;
  status: "DRAFT" | "PUBLISHED" | "RETIRED";
  rules: MappingRuleDto[];
  rulesSha256: string;
  testReport: { rulesSha256: string; testedAt: string; passed: boolean; total: number; failures: number; samples: TestSample[] } | null;
  testedSha256: string | null;
  testPassed: boolean | null;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
};

export type FlowMapping = {
  catalog: { targets: RuleField[]; sources: RuleField[]; ops: string[] };
  versions: MappingVersionDto[];
};

export const getFlowMapping = (companyId: string, flowId: string) =>
  companyGet<FlowMapping>(`/api/v1/flows/${encodeURIComponent(flowId)}/mapping`, companyId);

/** Writes for the mapping editor (server actions only). */
export async function sendMappingCommand(
  companyId: string,
  method: "POST" | "PUT",
  path: string,
  body?: unknown,
): Promise<MutationResult<MappingVersionDto> & { fields?: { field: string; reason: string }[] }> {
  try {
    const response = await fetch(`${apiBaseUrl}${path}`, {
      method,
      cache: "no-store",
      headers: {
        "X-Mystix-Company-Id": companyId,
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
    });
    const json = (await response.json().catch(() => null)) as
      | (MappingVersionDto & { errorCode?: string; fieldErrors?: { field: string; reason: string }[] })
      | null;
    if (!response.ok) return { ok: false, errorCode: json?.errorCode ?? "INTERNAL_ERROR", fields: json?.fieldErrors };
    return { ok: true, data: json as MappingVersionDto };
  } catch {
    return { ok: false, errorCode: "API_UNREACHABLE" };
  }
}
