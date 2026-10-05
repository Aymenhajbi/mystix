"use server";

import { redirect } from "next/navigation";
import { getCompany, portalCompanyId, submitInvoice, type ApiErrorBody } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";

/** What the portal form sends. Amounts stay strings: the backend parses them as BigDecimal. */
export type InvoiceDraft = {
  number: string;
  issueDate: string;
  dueDate: string;
  currency: string;
  buyerReference: string;
  purchaseOrderReference: string;
  note: string;
  seller: { taxIdentifier: string; tradeRegister: string; street: string; city: string; postalCode: string };
  buyer: {
    name: string;
    ice: string;
    gln: string;
    street: string;
    city: string;
    postalCode: string;
    countryCode: string;
  };
  lines: {
    itemName: string;
    quantity: string;
    unitCode: string;
    unitPrice: string;
    vatCategory: "S" | "Z";
    vatRate: string;
  }[];
};

const blank = (v: string) => (v.trim() === "" ? null : v.trim());
/** Accepts "12,50" as typed on a French keyboard; the backend only reads "12.50". */
const decimal = (v: string) => blank(v)?.replace(/\s/g, "").replace(",", ".") ?? null;

function failure(errorCode: string): { error: ApiErrorBody } {
  return {
    error: { errorCode, userMessage: null, suggestedAction: null, fieldErrors: [], ruleViolations: [], requestId: null },
  };
}

/**
 * Issues an invoice for the active environment, then opens it. The seller is always the active company:
 * its legal name and ICE come from the backend, never from the browser.
 */
export async function createInvoiceAction(lang: string, draft: InvoiceDraft): Promise<{ error: ApiErrorBody }> {
  if (!hasLocale(lang)) redirect("/");
  const companyId = await portalCompanyId();
  if (!companyId) return failure("NO_COMPANY");
  const company = await getCompany(companyId);
  if (company.kind !== "ok") return failure("API_UNREACHABLE");

  const result = await submitInvoice(companyId, {
    number: draft.number.trim(),
    issueDate: blank(draft.issueDate),
    dueDate: blank(draft.dueDate),
    currency: draft.currency.trim().toUpperCase(),
    buyerReference: blank(draft.buyerReference),
    purchaseOrderReference: blank(draft.purchaseOrderReference),
    note: blank(draft.note),
    seller: {
      name: company.data.legalName,
      ice: company.data.ice,
      taxIdentifier: blank(draft.seller.taxIdentifier) ?? company.data.taxIdentifier,
      tradeRegister: blank(draft.seller.tradeRegister),
      address: {
        street: blank(draft.seller.street),
        city: blank(draft.seller.city),
        postalCode: blank(draft.seller.postalCode),
        countryCode: "MA",
      },
    },
    buyer: {
      name: draft.buyer.name.trim(),
      ice: blank(draft.buyer.ice),
      gln: blank(draft.buyer.gln),
      address: {
        street: blank(draft.buyer.street),
        city: blank(draft.buyer.city),
        postalCode: blank(draft.buyer.postalCode),
        countryCode: draft.buyer.countryCode.trim().toUpperCase(),
      },
    },
    lines: draft.lines.map((line, i) => ({
      id: String(i + 1),
      quantity: decimal(line.quantity),
      unitCode: line.unitCode,
      unitPrice: decimal(line.unitPrice),
      itemName: line.itemName.trim(),
      // Zero-rated lines carry a 0 % rate (EN 16931 BR-Z-5); a standard rate is always typed by the user.
      vat: { category: line.vatCategory, ratePercent: line.vatCategory === "Z" ? "0" : decimal(line.vatRate) },
    })),
  });
  if (result.ok) redirect(`/${lang}/invoices/${result.invoiceId}?created=1`);
  return { error: result.error };
}
