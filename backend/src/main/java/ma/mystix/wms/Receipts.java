package ma.mystix.wms;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplier receipts (ADR-0014): drafted, then confirmed in one transaction that creates or completes the lots and
 * records a RECEIPT movement per line. Confirming twice does nothing the second time.
 */
@Service
public class Receipts {

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final Lots lots;
    private final Ledger ledger;
    private final AtelysAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    Receipts(JdbcClient jdbc, WmsCatalog catalog, Lots lots, Ledger ledger, AtelysAudit audit, JsonMapper json,
             Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.lots = lots;
        this.ledger = ledger;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record NewLine(UUID productId, BigDecimal quantity, String unit, UUID locationId, String lotNumber,
                          String supplierLot, Map<String, Object> attributes) {
    }

    public record NewReceipt(String number, UUID siteId, String supplier, String sourceDocument,
                             List<NewLine> lines) {
    }

    public record Line(int lineNo, UUID productId, String sku, String productName, BigDecimal quantity, String unit,
                       UUID locationId, String locationCode, String lotNumber, String supplierLot,
                       Map<String, Object> attributes, UUID lotId) {
    }

    public record Receipt(UUID id, String number, UUID siteId, String supplier, String sourceDocument, String status,
                          OffsetDateTime createdAt, OffsetDateTime confirmedAt, String createdBy, String confirmedBy,
                          List<Line> lines) {
    }

    @Transactional
    public Receipt create(UUID companyId, NewReceipt r, String actor) {
        WmsCatalog.Site site = catalog.site(companyId, r.siteId());
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "A receipt needs at least one line");
        }
        UUID id = UUID.randomUUID();
        String number = r.number() == null || r.number().isBlank() ? audit.nextNumber(companyId, "REC")
                : r.number().strip();
        try {
            jdbc.sql("""
                            INSERT INTO wms_receipt (id, company_id, number, site_id, supplier, source_document, status,
                                                     created_at, created_by)
                            VALUES (:id, :c, :n, :site, :supplier, :doc, 'DRAFT', :now, :actor)
                            """)
                    .param("id", id).param("c", companyId).param("n", number).param("site", site.id())
                    .param("supplier", r.supplier()).param("doc", r.sourceDocument())
                    .param("now", OffsetDateTime.now(clock)).param("actor", actor).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ATELYS_CODE_TAKEN, "Receipt " + number + " exists", e);
        }
        int no = 0;
        for (NewLine line : r.lines()) {
            WmsCatalog.Product product = catalog.product(companyId, line.productId());
            catalog.toBase(product, line.quantity(), line.unit());
            WmsCatalog.Location location = catalog.location(companyId, line.locationId());
            if (!location.siteId().equals(site.id()) || "TRANSIT".equals(location.type())) {
                throw new MystixException(ErrorCode.VALIDATION_FAILED,
                        "Location " + location.code() + " is not a receiving place of site " + site.code());
            }
            jdbc.sql("""
                            INSERT INTO wms_receipt_line (receipt_id, line_no, product_id, quantity, unit, location_id,
                                                          lot_number, supplier_lot, attributes)
                            VALUES (:r, :no, :p, :q, :u, :loc, :lot, :slot, CAST(:attrs AS jsonb))
                            """)
                    .param("r", id).param("no", ++no).param("p", product.id()).param("q", line.quantity())
                    .param("u", line.unit() == null || line.unit().isBlank() ? product.baseUnit()
                            : line.unit().strip().toUpperCase())
                    .param("loc", location.id()).param("lot", blankToNull(line.lotNumber()))
                    .param("slot", blankToNull(line.supplierLot()))
                    .param("attrs", json.writeValueAsString(line.attributes() == null ? Map.of() : line.attributes()))
                    .update();
        }
        return get(companyId, id);
    }

    /** Confirms a draft receipt: lots, movements and balances in one transaction. A confirmed receipt is returned as is. */
    @Transactional
    public Receipt confirm(UUID companyId, UUID receiptId, String actor) {
        String status = jdbc.sql("SELECT status FROM wms_receipt WHERE company_id = :c AND id = :id FOR UPDATE")
                .param("c", companyId).param("id", receiptId).query(String.class).optional()
                .orElseThrow(() -> WmsCatalog.notFound("receipt " + receiptId));
        Receipt receipt = get(companyId, receiptId);
        if ("CONFIRMED".equals(status)) {
            return receipt;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Line line : receipt.lines()) {
            WmsCatalog.Product product = catalog.product(companyId, line.productId());
            BigDecimal base = catalog.toBase(product, line.quantity(), line.unit());
            Lots.Lot lot = lots.create(companyId, product.id(), line.lotNumber(), "RECEIPT", product.receiptQuality(),
                    receipt.supplier(), line.supplierLot(), null, line.attributes(), now);
            ledger.post(companyId, new Ledger.Post("RECEIPT", product.id(), lot.id(), base, line.quantity(),
                    line.unit(), null, line.locationId(), "RECEIPT", receiptId, now, actor,
                    "Réception " + receipt.number()));
            jdbc.sql("UPDATE wms_receipt_line SET lot_id = :lot WHERE receipt_id = :r AND line_no = :no")
                    .param("lot", lot.id()).param("r", receiptId).param("no", line.lineNo()).update();
        }
        jdbc.sql("""
                        UPDATE wms_receipt SET status = 'CONFIRMED', confirmed_at = :now, received_at = :now,
                                               confirmed_by = :actor
                        WHERE id = :id
                        """)
                .param("now", now).param("actor", actor).param("id", receiptId).update();
        return get(companyId, receiptId);
    }

    public Receipt get(UUID companyId, UUID receiptId) {
        Receipt head = list(companyId, receiptId, 1).stream().findFirst()
                .orElseThrow(() -> WmsCatalog.notFound("receipt " + receiptId));
        List<Line> lines = new ArrayList<>(jdbc.sql("""
                        SELECT rl.*, p.sku, p.name AS product_name, loc.code AS location_code, l.lot_number AS lot_name
                        FROM wms_receipt_line rl
                        JOIN wms_product p ON p.id = rl.product_id
                        JOIN wms_location loc ON loc.id = rl.location_id
                        LEFT JOIN wms_lot l ON l.id = rl.lot_id
                        WHERE rl.receipt_id = :r ORDER BY rl.line_no
                        """)
                .param("r", receiptId)
                .query((rs, n) -> new Line(rs.getInt("line_no"), rs.getObject("product_id", UUID.class),
                        rs.getString("sku"), rs.getString("product_name"), rs.getBigDecimal("quantity"),
                        rs.getString("unit"), rs.getObject("location_id", UUID.class), rs.getString("location_code"),
                        rs.getString("lot_name") != null ? rs.getString("lot_name") : rs.getString("lot_number"),
                        rs.getString("supplier_lot"),
                        json.readValue(rs.getString("attributes"), new TypeReference<Map<String, Object>>() { }),
                        rs.getObject("lot_id", UUID.class)))
                .list());
        return new Receipt(head.id(), head.number(), head.siteId(), head.supplier(), head.sourceDocument(),
                head.status(), head.createdAt(), head.confirmedAt(), head.createdBy(), head.confirmedBy(), lines);
    }

    public List<Receipt> list(UUID companyId, UUID receiptId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM wms_receipt
                        WHERE company_id = :c AND (CAST(:id AS uuid) IS NULL OR id = :id)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("id", receiptId).param("limit", limit)
                .query((rs, n) -> new Receipt(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("site_id", UUID.class), rs.getString("supplier"),
                        rs.getString("source_document"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("confirmed_at", OffsetDateTime.class), rs.getString("created_by"),
                        rs.getString("confirmed_by"), List.of()))
                .list();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
