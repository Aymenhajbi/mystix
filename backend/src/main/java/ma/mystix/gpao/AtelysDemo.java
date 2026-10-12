package ma.mystix.gpao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import ma.mystix.auth.Access;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.wms.Lots;
import ma.mystix.wms.Receipts;
import ma.mystix.wms.WmsCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fictitious demonstration data for Atelys (ADR-0014): one site and a depot, warehouses, locations, wood products,
 * a validated bill of materials, a confirmed receipt and a work order with issues and one declaration. Every name
 * says "démo"; nothing comes from a real company. Only when {@code mystix.atelys.demo-enabled} is on (developer
 * profile); never in production. Running it twice changes nothing.
 */
@Service
public class AtelysDemo {

    private final WmsCatalog catalog;
    private final Lots lots;
    private final Receipts receipts;
    private final Boms boms;
    private final WorkOrders workOrders;

    AtelysDemo(WmsCatalog catalog, Lots lots, Receipts receipts, Boms boms, WorkOrders workOrders) {
        this.catalog = catalog;
        this.lots = lots;
        this.receipts = receipts;
        this.boms = boms;
        this.workOrders = workOrders;
    }

    public record Result(boolean created, UUID siteId, UUID workOrderId) {
    }

    @Transactional
    public Result seed(UUID companyId, String actor) {
        var existing = catalog.sites(companyId).stream().filter(s -> "DEMO".equals(s.code())).findFirst();
        if (existing.isPresent()) {
            UUID wo = workOrders.list(companyId, null, null, 1).stream().map(WorkOrders.WorkOrder::id).findFirst()
                    .orElse(null);
            return new Result(false, existing.get().id(), wo);
        }
        catalog.createUnit(companyId, "U", "Unité", "COUNT");
        catalog.createUnit(companyId, "KG", "Kilogramme", "MASS");
        catalog.createUnit(companyId, "M3", "Mètre cube", "VOLUME");

        WmsCatalog.Site site = catalog.createSite(companyId, "DEMO", "Site de démonstration (fictif)");
        WmsCatalog.Site depot = catalog.createSite(companyId, "DEMO2", "Dépôt de démonstration (fictif)");
        var mat = catalog.createWarehouse(companyId, site.id(), "MAT", "Matières premières (démo)");
        var pf = catalog.createWarehouse(companyId, site.id(), "PF", "Produits finis (démo)");
        var d2 = catalog.createWarehouse(companyId, depot.id(), "D2", "Dépôt (démo)");
        var rec = catalog.createLocation(companyId, mat.id(), "REC-01", "Quai", "RECEIVING");
        var stkA = catalog.createLocation(companyId, mat.id(), "STK-A01", "Allée A", "STORAGE");
        catalog.createLocation(companyId, mat.id(), "STK-A02", "Allée A", "STORAGE");
        catalog.createLocation(companyId, mat.id(), "PROD-L1", "Ligne 1", "PRODUCTION");
        catalog.createLocation(companyId, mat.id(), "QUA-01", "Quarantaine", "QUARANTINE");
        var pf01 = catalog.createLocation(companyId, pf.id(), "PF-01", "Stock PF", "STORAGE");
        catalog.createLocation(companyId, pf.id(), "EXP-01", "Quai expédition", "SHIPPING");
        catalog.createLocation(companyId, d2.id(), "D2-01", "Stock dépôt", "STORAGE");

        var planche = catalog.createProduct(companyId, new WmsCatalog.NewProduct("DEMO-PLANCHE",
                "Planche résineux 1200 x 100 x 22 (démo)", "Données fictives", "RAW_MATERIAL", "U", true, "PENDING",
                new BigDecimal("500")));
        var de = catalog.createProduct(companyId, new WmsCatalog.NewProduct("DEMO-DE", "Dé moulé (démo)",
                "Données fictives", "COMPONENT", "U", true, "RELEASED", new BigDecimal("300")));
        var clou = catalog.createProduct(companyId, new WmsCatalog.NewProduct("DEMO-CLOU", "Clous (démo)",
                "Données fictives, non suivi par lot", "CONSUMABLE", "KG", false, "RELEASED", new BigDecimal("10")));
        var palette = catalog.createProduct(companyId, new WmsCatalog.NewProduct("DEMO-PALETTE",
                "Palette 1200 x 800 (démo)", "Données fictives", "FINISHED", "U", true, "PENDING", null));
        var sciure = catalog.createProduct(companyId, new WmsCatalog.NewProduct("DEMO-SCIURE", "Sciure (démo)",
                "Données fictives", "BY_PRODUCT", "KG", true, "PENDING", null));

        Boms.Bom bom = boms.create(companyId, new Boms.NewBom(palette.id(), BigDecimal.ONE, null, null,
                "Nomenclature d'exemple, quantités fictives à valider avec le métier",
                List.of(new Boms.NewLine(planche.id(), new BigDecimal("9"), new BigDecimal("3"), false),
                        new Boms.NewLine(de.id(), new BigDecimal("9"), BigDecimal.ZERO, false),
                        new Boms.NewLine(clou.id(), new BigDecimal("0.15"), new BigDecimal("5"), false)),
                List.of(new Boms.Operation(10, "Découpe", "Scie (démo)", new BigDecimal("1.5"), null, null),
                        new Boms.Operation(20, "Assemblage et clouage", "Ligne 1 (démo)", new BigDecimal("2"), null,
                                null),
                        new Boms.Operation(30, "Contrôle", "Poste qualité (démo)", new BigDecimal("0.5"), null,
                                "Équerrage et clouage"))), actor);
        boms.validate(companyId, bom.id(), actor);

        Receipts.Receipt receipt = receipts.create(companyId, new Receipts.NewReceipt(null, site.id(),
                "Fournisseur bois (fictif)", "BL-DEMO-001", List.of(
                new Receipts.NewLine(planche.id(), new BigDecimal("1000"), "U", stkA.id(), "DEMO-PLA-001",
                        "FRN-LOT-DEMO", Map.of("essence", "Pin (fictif)", "humidite_pct", 18)),
                new Receipts.NewLine(de.id(), new BigDecimal("1200"), "U", stkA.id(), "DEMO-DE-001", null, Map.of()),
                new Receipts.NewLine(clou.id(), new BigDecimal("50"), "KG", rec.id(), null, null, Map.of()))), actor);
        receipt = receipts.confirm(companyId, receipt.id(), actor);
        UUID plancheLot = receipt.lines().get(0).lotId();
        UUID deLot = receipt.lines().get(1).lotId();
        UUID clouLot = receipt.lines().get(2).lotId();
        lots.setQuality(companyId, plancheLot, "RELEASED", "Contrôle humidité conforme (démo)", actor);

        WorkOrders.WorkOrder wo = workOrders.create(companyId, new WorkOrders.NewWorkOrder(null, palette.id(),
                bom.id(), new BigDecimal("100"), site.id(), "Ligne 1 (démo)", pf01.id(), null, null, 2,
                "CMD-DEMO-001", actor), actor);
        workOrders.transition(companyId, wo.id(), "RELEASED", null, actor);
        workOrders.reserveMaterials(companyId, wo.id(), actor);
        workOrders.issue(companyId, wo.id(), plancheLot, stkA.id(), new BigDecimal("470"), "U", "demo-pla", null,
                actor);
        workOrders.issue(companyId, wo.id(), deLot, stkA.id(), new BigDecimal("450"), "U", "demo-de", null, actor);
        workOrders.issue(companyId, wo.id(), clouLot, rec.id(), new BigDecimal("7.5"), "KG", "demo-clou", null,
                actor);
        workOrders.report(companyId, wo.id(), new WorkOrders.NewReport("demo-1", new BigDecimal("48"),
                new BigDecimal("2"), "Planche fendue (démo)", "Ligne 1 (démo)", "Opérateur démo", null, null, null,
                List.of(new WorkOrders.NewResidue(sciure.id(), new BigDecimal("35"), "KG", "RECOVERED", "GRANULES"),
                        new WorkOrders.NewResidue(null, new BigDecimal("4"), "KG", "WASTE", "ELIMINATION"))), actor);
        return new Result(true, site.id(), wo.id());
    }
}

@RestController
class AtelysDemoController {

    private final AtelysDemo demo;
    private final Access access;
    private final boolean enabled;

    AtelysDemoController(AtelysDemo demo, Access access,
                         @Value("${mystix.atelys.demo-enabled:false}") boolean enabled) {
        this.demo = demo;
        this.access = access;
        this.enabled = enabled;
    }

    @PostMapping(value = "/api/v1/atelys/demo", produces = MediaType.APPLICATION_JSON_VALUE)
    AtelysDemo.Result seed(@RequestHeader("X-Mystix-Company-Id") UUID companyId, HttpServletRequest http) {
        if (!enabled) {
            throw new MystixException(ErrorCode.RESOURCE_NOT_FOUND, "Atelys demo data is disabled");
        }
        access.requireManager(http, companyId);
        return demo.seed(companyId, access.actor(http));
    }
}
