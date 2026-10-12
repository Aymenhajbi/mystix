package ma.mystix.wms;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ma.mystix.tenant.Company;
import ma.mystix.tenant.CompanyRegistered;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every Mystix client gets its Atelys environment (ADR-0014, amended): the usual units and, when the company has no
 * site yet, a main site with one warehouse and one location per type. Created in the registration transaction of the
 * company, and at startup for companies registered before. Idempotent: never touches an environment that exists.
 */
@Service
public class AtelysEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(AtelysEnvironment.class);

    record UnitSeed(String code, String name, String dimension) {
    }

    record LocationSeed(String code, String zone, String type) {
    }

    static final List<UnitSeed> UNITS = List.of(
            new UnitSeed("U", "Unité", "COUNT"),
            new UnitSeed("KG", "Kilogramme", "MASS"),
            new UnitSeed("M", "Mètre", "LENGTH"),
            new UnitSeed("M2", "Mètre carré", "AREA"),
            new UnitSeed("M3", "Mètre cube", "VOLUME"),
            new UnitSeed("L", "Litre", "VOLUME"));

    static final List<LocationSeed> LOCATIONS = List.of(
            new LocationSeed("REC-01", "Réception", "RECEIVING"),
            new LocationSeed("STK-01", "Stockage", "STORAGE"),
            new LocationSeed("PROD-01", "Production", "PRODUCTION"),
            new LocationSeed("QUA-01", "Quarantaine", "QUARANTINE"),
            new LocationSeed("EXP-01", "Expédition", "SHIPPING"));

    public record Environment(UUID companyId, UUID defaultSiteId, OffsetDateTime provisionedAt) {
    }

    private final JdbcClient jdbc;
    private final CompanyService companies;
    private final TransactionTemplate tx;
    private final Clock clock;

    AtelysEnvironment(JdbcClient jdbc, CompanyService companies, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.tx = tx;
        this.clock = clock;
    }

    @EventListener
    void onCompanyRegistered(CompanyRegistered event) {
        provision(event.company().id());
    }

    /** Companies registered before Atelys existed get their environment at startup. */
    @EventListener(ApplicationReadyEvent.class)
    void backfill() {
        int created = 0;
        for (Company company : companies.list()) {
            Boolean done = tx.execute(status -> provision(company.id()));
            if (Boolean.TRUE.equals(done)) {
                created++;
            }
        }
        if (created > 0) {
            LOG.info("Atelys environment created for {} existing companies", created);
        }
    }

    /** Creates the environment of a company unless it exists. Returns true when it was created now. */
    @Transactional
    public boolean provision(UUID companyId) {
        // One provisioning per company at a time, whatever the caller (registration, startup on several nodes).
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('atelys-env:' || CAST(:c AS text)))")
                .param("c", companyId).query().singleRow();
        if (find(companyId).isPresent()) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (UnitSeed u : UNITS) {
            jdbc.sql("""
                            INSERT INTO wms_unit (company_id, code, name, dimension) VALUES (:c, :code, :name, :d)
                            ON CONFLICT (company_id, code) DO NOTHING
                            """)
                    .param("c", companyId).param("code", u.code()).param("name", u.name()).param("d", u.dimension())
                    .update();
        }
        UUID siteId = jdbc.sql("SELECT id FROM wms_site WHERE company_id = :c ORDER BY created_at, code LIMIT 1")
                .param("c", companyId).query(UUID.class).optional().orElseGet(() -> mainSite(companyId, now));
        jdbc.sql("INSERT INTO atelys_environment (company_id, default_site_id, provisioned_at) VALUES (:c, :s, :now)")
                .param("c", companyId).param("s", siteId).param("now", now).update();
        return true;
    }

    public Optional<Environment> find(UUID companyId) {
        return jdbc.sql("SELECT company_id, default_site_id, provisioned_at FROM atelys_environment WHERE company_id = :c")
                .param("c", companyId)
                .query((rs, n) -> new Environment(rs.getObject("company_id", UUID.class),
                        rs.getObject("default_site_id", UUID.class),
                        rs.getObject("provisioned_at", OffsetDateTime.class)))
                .optional();
    }

    private UUID mainSite(UUID companyId, OffsetDateTime now) {
        UUID site = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO wms_site (id, company_id, code, name, active, created_at)
                        VALUES (:id, :c, 'PRINCIPAL', 'Site principal', TRUE, :now)
                        """)
                .param("id", site).param("c", companyId).param("now", now).update();
        jdbc.sql("""
                        INSERT INTO wms_warehouse (id, company_id, site_id, code, name, active, created_at)
                        VALUES (:id, :c, :site, 'MAG', 'Magasin principal', TRUE, :now)
                        """)
                .param("id", warehouse).param("c", companyId).param("site", site).param("now", now).update();
        location(companyId, site, null, "TRANSIT", null, "TRANSIT", now);
        for (LocationSeed l : LOCATIONS) {
            location(companyId, site, warehouse, l.code(), l.zone(), l.type(), now);
        }
        return site;
    }

    private void location(UUID companyId, UUID site, UUID warehouse, String code, String zone, String type,
                          OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO wms_location (id, company_id, site_id, warehouse_id, code, zone, type, active,
                                                  created_at)
                        VALUES (:id, :c, :site, :w, :code, :zone, :type, TRUE, :now)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("site", site).param("w", warehouse)
                .param("code", code).param("zone", zone).param("type", type).param("now", now).update();
    }
}
