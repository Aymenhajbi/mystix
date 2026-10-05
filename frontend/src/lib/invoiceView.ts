import type { InvoiceDetail, InvoiceStatus, InvoiceSummary } from "./api";

/** Visual severity, as in the Cockpit journal. */
export type Severity = "ok" | "warn" | "err" | "info";

export const severityOf = (status: string): Severity =>
  status === "CLEARED"
    ? "ok"
    : status === "VALIDATED" || status === "PENDING_VALIDATION"
      ? "warn"
      : status.startsWith("CLEARANCE_") || status === "VALIDATION_REJECTED"
        ? "err"
        : "info";

export type StatusFilter = "all" | "toValidate" | "cleared" | "rejected" | "pending";

export const statusFilters: StatusFilter[] = ["all", "toValidate", "cleared", "rejected", "pending"];

const filterStatus: Record<Exclude<StatusFilter, "all">, InvoiceStatus> = {
  toValidate: "PENDING_VALIDATION",
  cleared: "CLEARED",
  rejected: "CLEARANCE_REJECTED",
  pending: "VALIDATED",
};

export const isStatusFilter = (value: string | undefined): value is StatusFilter =>
  value !== undefined && (statusFilters as string[]).includes(value);

export function matchesFilter(invoice: InvoiceSummary, filter: StatusFilter) {
  return filter === "all" || invoice.status === filterStatus[filter];
}

export function matchesQuery(invoice: InvoiceSummary, query: string) {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [invoice.number, invoice.buyerName ?? "", invoice.clearance.reference ?? ""].some((v) =>
    v.toLowerCase().includes(q),
  );
}

/**
 * Exact decimal sum of amounts given as strings ("1586.80"), per currency.
 * Money never goes through floating point here.
 */
export function sumByCurrency(invoices: InvoiceSummary[]) {
  const totals = new Map<string, bigint>();
  for (const invoice of invoices) {
    if (!invoice.payableAmount || !invoice.currency) continue;
    totals.set(invoice.currency, (totals.get(invoice.currency) ?? 0n) + toMinor(invoice.payableAmount));
  }
  return [...totals.entries()].map(([currency, minor]) => ({ currency, amount: fromMinor(minor) }));
}

function toMinor(amount: string) {
  const negative = amount.startsWith("-");
  const [whole, fraction = ""] = amount.replace("-", "").split(".");
  const minor = BigInt(whole) * 100n + BigInt((fraction + "00").slice(0, 2));
  return negative ? -minor : minor;
}

function fromMinor(minor: bigint) {
  const negative = minor < 0n;
  const abs = negative ? -minor : minor;
  const fraction = (abs % 100n).toString().padStart(2, "0");
  return `${negative ? "-" : ""}${abs / 100n}.${fraction}`;
}

export function cockpitStats(invoices: InvoiceSummary[]) {
  const count = (status: InvoiceStatus) => invoices.filter((i) => i.status === status).length;
  const cleared = count("CLEARED");
  const rejected = count("CLEARANCE_REJECTED");
  const pending = count("VALIDATED");
  const decided = cleared + rejected;
  return {
    total: invoices.length,
    cleared,
    rejected,
    pending,
    /** Backdated invoices waiting for the administrator, oldest received first. */
    toValidate: invoices
      .filter((i) => i.status === "PENDING_VALIDATION")
      .sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1)),
    clearanceRate: decided === 0 ? null : (cleared / decided) * 100,
    clearedAmounts: sumByCurrency(invoices.filter((i) => i.status === "CLEARED")),
    lastActivity: invoices.reduce<string | null>((latest, i) => {
      const at = i.clearance.at ?? i.createdAt;
      return latest === null || at > latest ? at : latest;
    }, null),
  };
}

/** One journal line per invoice: its latest known state. */
export type JournalEntry = {
  id: string;
  at: string;
  severity: Severity;
  status: InvoiceStatus;
  number: string;
  buyerName: string | null;
  payableAmount: string | null;
  currency: string | null;
  reference: string | null;
  simulated: boolean;
};

export function journal(invoices: InvoiceSummary[]): JournalEntry[] {
  return invoices
    .map((i) => ({
      id: i.id,
      at: i.clearance.at ?? i.createdAt,
      severity: severityOf(i.status),
      status: i.status,
      number: i.number,
      buyerName: i.buyerName,
      payableAmount: i.payableAmount,
      currency: i.currency,
      reference: i.clearance.reference,
      simulated: i.clearance.simulated === true,
    }))
    .sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0));
}

export type StepState = "done" | "err" | "warn";

/**
 * Processing steps of a stored invoice. An invoice is only stored once mapping, UBL generation, XSD and
 * EN 16931 checks passed, so those steps are done; the last one reflects the clearance outcome.
 */
export function processingSteps(invoice: Pick<InvoiceDetail, "status">): { key: StepKey; state: StepState }[] {
  const clearance: StepState =
    invoice.status === "CLEARED"
      ? "done"
      : invoice.status === "CLEARANCE_REJECTED" || invoice.status === "VALIDATION_REJECTED"
        ? "err"
        : "warn";
  return [
    { key: "received", state: "done" },
    { key: "canonical", state: "done" },
    { key: "ubl", state: "done" },
    { key: "xsd", state: "done" },
    { key: "en16931", state: "done" },
    { key: "clearance", state: clearance },
  ];
}

export type StepKey = "received" | "canonical" | "ubl" | "xsd" | "en16931" | "clearance";
