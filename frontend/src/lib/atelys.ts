import { getForCompany } from "./api";

/** Atelys (GPAO + WMS, ADR-0014): types of the /api/v1/wms and /api/v1/gpao endpoints. Quantities are numbers in JSON. */

export type Quality = "PENDING" | "RELEASED" | "BLOCKED" | "REJECTED";
export type ProductCategory = "RAW_MATERIAL" | "COMPONENT" | "CONSUMABLE" | "BY_PRODUCT" | "SEMI_FINISHED" | "FINISHED";
export type LocationType = "RECEIVING" | "STORAGE" | "PRODUCTION" | "QUARANTINE" | "SHIPPING" | "TRANSIT";
export type WorkOrderStatus =
  | "DRAFT"
  | "PLANNED"
  | "RELEASED"
  | "IN_PROGRESS"
  | "ON_HOLD"
  | "COMPLETED"
  | "CLOSED"
  | "CANCELLED";

export type Unit = { code: string; name: string; dimension: string };
export type Product = {
  id: string;
  sku: string;
  name: string;
  description: string | null;
  category: ProductCategory;
  baseUnit: string;
  lotTracked: boolean;
  receiptQuality: "PENDING" | "RELEASED";
  alertThreshold: number | null;
  active: boolean;
  conversions: Record<string, number>;
};
export type Site = { id: string; code: string; name: string; active: boolean; transitLocationId: string };
export type Warehouse = { id: string; siteId: string; code: string; name: string; active: boolean };
export type Location = {
  id: string;
  siteId: string;
  warehouseId: string | null;
  code: string;
  zone: string | null;
  type: LocationType;
  active: boolean;
};
export type Lot = {
  id: string;
  productId: string;
  sku: string;
  productName: string;
  unit: string;
  lotNumber: string;
  origin: string;
  qualityStatus: Quality;
  supplier: string | null;
  supplierLot: string | null;
  workOrderId: string | null;
  publicCode: string;
  attributes: Record<string, unknown>;
  receivedAt: string | null;
  createdAt: string;
};
export type StockLine = {
  productId: string;
  sku: string;
  productName: string;
  category: ProductCategory;
  unit: string;
  lotId: string;
  lotNumber: string;
  quality: Quality;
  publicCode: string;
  locationId: string;
  locationCode: string;
  locationType: LocationType;
  siteId: string;
  siteCode: string;
  quantity: number;
  updatedAt: string;
};
export type ProductStock = {
  productId: string;
  sku: string;
  productName: string;
  category: ProductCategory;
  unit: string;
  physical: number;
  blocked: number;
  reserved: number;
  available: number;
  inTransit: number;
  alertThreshold: number | null;
  belowThreshold: boolean;
};
export type Availability = { physical: number; blocked: number; reserved: number; available: number; inTransit: number };
export type Movement = {
  id: string;
  type: string;
  productId: string;
  sku: string;
  lotId: string;
  lotNumber: string;
  quantity: number;
  unit: string;
  enteredQuantity: number;
  enteredUnit: string;
  fromLocationId: string | null;
  fromLocation: string | null;
  toLocationId: string | null;
  toLocation: string | null;
  referenceType: string;
  referenceId: string;
  occurredAt: string;
  actor: string | null;
  reason: string | null;
};
export type GenealogyNode = {
  lotId: string;
  lotNumber: string;
  productId: string;
  sku: string;
  productName: string;
  quality: Quality;
  origin: string;
  workOrderId: string | null;
  depth: number;
};
export type Genealogy = { upstream: GenealogyNode[]; downstream: GenealogyNode[] };
export type AuditEntry = {
  id: string;
  action: string;
  objectType: string;
  objectId: string;
  actor: string | null;
  reason: string | null;
  detail: string | null;
  at: string;
};
export type LotView = {
  lot: Lot;
  stock: StockLine[];
  availability: Availability;
  movements: Movement[];
  genealogy: Genealogy;
  audit: AuditEntry[];
};
export type ReceiptLine = {
  lineNo: number;
  productId: string;
  sku: string;
  productName: string;
  quantity: number;
  unit: string;
  locationId: string;
  locationCode: string;
  lotNumber: string | null;
  supplierLot: string | null;
  lotId: string | null;
};
export type Receipt = {
  id: string;
  number: string;
  siteId: string;
  supplier: string | null;
  sourceDocument: string | null;
  status: "DRAFT" | "CONFIRMED";
  createdAt: string;
  confirmedAt: string | null;
  createdBy: string | null;
  confirmedBy: string | null;
  lines: ReceiptLine[];
};
export type Transfer = {
  id: string;
  number: string;
  lotId: string;
  lotNumber: string;
  productId: string;
  sku: string;
  quantity: number;
  unit: string;
  fromLocationId: string;
  fromLocation: string;
  fromSiteId: string;
  toLocationId: string;
  toLocation: string;
  toSiteId: string;
  status: "DRAFT" | "SHIPPED" | "RECEIVED" | "CANCELLED";
  createdAt: string;
  shippedAt: string | null;
  receivedAt: string | null;
};
export type InventoryLine = {
  lotId: string;
  lotNumber: string;
  productId: string;
  sku: string;
  productName: string;
  unit: string;
  locationId: string;
  locationCode: string;
  expected: number;
  counted: number | null;
  delta: number;
  reason: string | null;
};
export type InventoryCount = {
  id: string;
  number: string;
  warehouseId: string;
  status: "OPEN" | "SUBMITTED" | "APPROVED";
  createdAt: string;
  submittedAt: string | null;
  approvedAt: string | null;
  createdBy: string | null;
  approvedBy: string | null;
  lines: InventoryLine[];
};
export type ShipmentLine = {
  lineNo: number;
  lotId: string;
  lotNumber: string;
  sku: string;
  productName: string;
  locationCode: string;
  quantity: number;
  unit: string;
  picked: number | null;
};
export type Shipment = {
  id: string;
  number: string;
  siteId: string;
  customer: string | null;
  orderReference: string | null;
  status: "PICKING" | "SHIPPED";
  createdAt: string;
  shippedAt: string | null;
  lines: ShipmentLine[];
};
export type BomLine = {
  lineNo: number;
  componentId: string;
  sku: string;
  name: string;
  unit: string;
  quantity: number;
  lossPercent: number;
  substitutionAllowed: boolean;
};
export type BomOperation = {
  sequence: number;
  name: string;
  workstation: string | null;
  standardMinutes: number | null;
  instructions: string | null;
  qualityCheck: string | null;
};
export type Bom = {
  id: string;
  productId: string;
  sku: string;
  productName: string;
  unit: string;
  version: number;
  referenceQuantity: number;
  status: "DRAFT" | "VALIDATED" | "OBSOLETE";
  note: string | null;
  createdAt: string;
  createdBy: string | null;
  validatedAt: string | null;
  validatedBy: string | null;
  lines: BomLine[];
  operations: BomOperation[];
};
export type WorkOrder = {
  id: string;
  number: string;
  productId: string;
  sku: string;
  productName: string;
  unit: string;
  bomId: string;
  bomVersion: number;
  plannedQuantity: number;
  siteId: string;
  productionLine: string | null;
  outputLocationId: string;
  outputLocation: string;
  plannedStart: string | null;
  plannedEnd: string | null;
  priority: number;
  status: WorkOrderStatus;
  customerReference: string | null;
  producedQuantity: number;
  goodQuantity: number;
  scrapQuantity: number;
  responsible: string | null;
  createdAt: string;
  updatedAt: string;
};
export type Material = {
  componentId: string;
  sku: string;
  name: string;
  unit: string;
  required: number;
  reserved: number;
  issued: number;
  remaining: number;
  available: number;
};
export type Consumption = {
  id: string;
  kind: "ISSUE" | "RETURN";
  sku: string;
  lotId: string;
  lotNumber: string;
  locationCode: string;
  quantity: number;
  unit: string;
  justification: string | null;
  actor: string | null;
  at: string;
};
export type Residue = {
  id: string;
  sku: string | null;
  quantity: number;
  unit: string;
  fate: "WASTE" | "RECOVERED" | "PENDING";
  destination: string | null;
  lotId: string | null;
  lotNumber: string | null;
};
export type Report = {
  id: string;
  reportKey: string;
  goodQuantity: number;
  scrapQuantity: number;
  scrapCause: string | null;
  productionLine: string | null;
  operator: string | null;
  comment: string | null;
  outputLotId: string | null;
  outputLotNumber: string | null;
  reportedAt: string;
  actor: string | null;
  residues: Residue[];
};
export type History = { fromStatus: string | null; toStatus: string; actor: string | null; reason: string | null; at: string };
export type ComponentVariance = {
  componentId: string;
  sku: string;
  unit: string;
  theoretical: number;
  issued: number;
  deviation: number | null;
};
export type Variance = {
  planned: number;
  produced: number;
  good: number;
  scrap: number;
  completion: number | null;
  scrapRate: number | null;
  components: ComponentVariance[];
};
export type WorkOrderDetail = {
  order: WorkOrder;
  bom: Bom;
  materials: Material[];
  consumptions: Consumption[];
  reports: Report[];
  history: History[];
  variance: Variance;
};
export type Dashboard = {
  workOrdersByStatus: Partial<Record<WorkOrderStatus, number>>;
  active: WorkOrder[];
  lastDays: { day: string; good: number; scrap: number }[];
  lotsPendingQuality: number;
  lotsBlocked: number;
  belowThreshold: ProductStock[];
  at: string;
};

const q = (params: Record<string, string | undefined>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v) s.set(k, v);
  const text = s.toString();
  return text ? `?${text}` : "";
};

export const atelys = {
  units: () => getForCompany<Unit[]>("/api/v1/wms/units"),
  products: (category?: string) => getForCompany<Product[]>(`/api/v1/wms/products${q({ category })}`),
  sites: () => getForCompany<Site[]>("/api/v1/wms/sites"),
  warehouses: () => getForCompany<Warehouse[]>("/api/v1/wms/warehouses"),
  locations: (siteId?: string) => getForCompany<Location[]>(`/api/v1/wms/locations${q({ siteId })}`),
  stockLines: (f: { siteId?: string; productId?: string; lotId?: string; quality?: string; q?: string }) =>
    getForCompany<StockLine[]>(`/api/v1/wms/stock${q({ ...f, limit: "500" })}`),
  productStock: (siteId?: string, search?: string) =>
    getForCompany<ProductStock[]>(`/api/v1/wms/stock/products${q({ siteId, q: search })}`),
  movements: (f: { productId?: string; lotId?: string; referenceId?: string; limit?: string }) =>
    getForCompany<Movement[]>(`/api/v1/wms/movements${q(f)}`),
  lots: (search?: string) => getForCompany<Lot[]>(`/api/v1/wms/lots${q({ q: search, limit: "100" })}`),
  scan: (code: string) => getForCompany<Lot[]>(`/api/v1/wms/lots/scan${q({ code })}`),
  lot: (id: string) => getForCompany<LotView>(`/api/v1/wms/lots/${encodeURIComponent(id)}`),
  receipts: () => getForCompany<Receipt[]>("/api/v1/wms/receipts"),
  receipt: (id: string) => getForCompany<Receipt>(`/api/v1/wms/receipts/${encodeURIComponent(id)}`),
  transfers: () => getForCompany<Transfer[]>("/api/v1/wms/transfers"),
  inventories: () => getForCompany<InventoryCount[]>("/api/v1/wms/inventories"),
  inventory: (id: string) => getForCompany<InventoryCount>(`/api/v1/wms/inventories/${encodeURIComponent(id)}`),
  shipments: () => getForCompany<Shipment[]>("/api/v1/wms/shipments"),
  shipment: (id: string) => getForCompany<Shipment>(`/api/v1/wms/shipments/${encodeURIComponent(id)}`),
  boms: () => getForCompany<Bom[]>("/api/v1/gpao/boms"),
  bom: (id: string) => getForCompany<Bom>(`/api/v1/gpao/boms/${encodeURIComponent(id)}`),
  workOrders: (status?: string) => getForCompany<WorkOrder[]>(`/api/v1/gpao/work-orders${q({ status })}`),
  workOrder: (id: string) => getForCompany<WorkOrderDetail>(`/api/v1/gpao/work-orders/${encodeURIComponent(id)}`),
  dashboard: () => getForCompany<Dashboard>("/api/v1/gpao/dashboard"),
};
