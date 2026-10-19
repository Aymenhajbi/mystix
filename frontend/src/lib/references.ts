import { getForCompany } from "./api";
import type { Product } from "./atelys";

/** Open product reference (ADR-0020): Open Food / Beauty / Products Facts, ODbL. */

export type ReferenceMatch = {
  source: "OFF" | "OBF" | "OPF";
  sourceLabel: string;
  code: string;
  name: string | null;
  brands: string | null;
  quantity: string | null;
  categories: string | null;
  countries: string | null;
  lastModified: string | null;
  url: string;
};

export type ReferenceLookup = {
  input: string;
  valid: boolean;
  gtin: string | null;
  display: string | null;
  length: number | null;
  prefix: "GS1_MOROCCO" | "RESTRICTED_CIRCULATION" | "OTHER" | null;
  matches: ReferenceMatch[];
  attribution: string;
};

export type ReferenceImport = {
  id: string;
  source: "OFF" | "OBF" | "OPF";
  fileName: string;
  fileSha256: string | null;
  status: "RUNNING" | "DONE" | "FAILED";
  rowsRead: number;
  rowsImported: number;
  rowsNoCode: number;
  rowsBadCheck: number;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
};

export type ReferenceStatus = { counts: Partial<Record<"OFF" | "OBF" | "OPF", number>>; imports: ReferenceImport[]; licence: string };

export type ProductScan = { product: Product | null; reference: ReferenceLookup };

export const references = {
  status: () => getForCompany<ReferenceStatus>("/api/v1/referential/products/status"),
  scan: (code: string) => getForCompany<ProductScan>(`/api/v1/wms/products/by-gtin/${encodeURIComponent(code)}`),
};
