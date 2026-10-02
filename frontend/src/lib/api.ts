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

export const listInvoices = () => companyGet<InvoiceSummary[]>("/api/v1/invoices?limit=50");

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
