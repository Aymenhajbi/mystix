/**
 * Server-side client of the Mystix backend. Only imported by Server Components and Route Handlers:
 * the company identifier never reaches the browser.
 *
 * TODO(auth): the portal company comes from MYSTIX_PORTAL_COMPANY_ID until authentication exists (Lot 8).
 */
export const apiBaseUrl =
  process.env.MYSTIX_API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export const portalCompanyId = () => process.env.MYSTIX_PORTAL_COMPANY_ID?.trim() || null;

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

export const getPortalCompany = () => {
  const company = portalCompanyId();
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

/** Upper bound of log lines fetched at once. */
export const LOG_WINDOW = 500;

export const listLogs = (options: { level?: LogLevel; invoiceId?: string; limit?: number } = {}) => {
  const p = new URLSearchParams({ level: options.level ?? "INFO", limit: String(options.limit ?? LOG_WINDOW) });
  if (options.invoiceId) p.set("invoiceId", options.invoiceId);
  return companyGet<LogEntry[]>(`/api/v1/logs?${p}`);
};

/** Request body kept with a rejected submission, for the payload route. */
export async function fetchLogPayload(id: string): Promise<ApiResult<ArrayBuffer>> {
  const company = portalCompanyId();
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
  const company = portalCompanyId();
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
  const company = portalCompanyId();
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

async function companyGet<T>(path: string): Promise<ApiResult<T>> {
  const company = portalCompanyId();
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
