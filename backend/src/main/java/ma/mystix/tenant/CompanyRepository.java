package ma.mystix.tenant;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CompanyRepository {

    private final JdbcClient jdbc;

    CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Company company) {
        jdbc.sql("""
                INSERT INTO company (id, ice, legal_name, tax_identifier, created_at)
                VALUES (:id, :ice, :legalName, :taxIdentifier, :createdAt)
                """)
                .param("id", company.id())
                .param("ice", company.ice().value())
                .param("legalName", company.legalName())
                .param("taxIdentifier", company.taxIdentifier())
                .param("createdAt", company.createdAt())
                .update();
    }

    Optional<Company> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, ice, legal_name, tax_identifier, created_at
                FROM company
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new Company(
                        rs.getObject("id", UUID.class),
                        new Ice(rs.getString("ice")),
                        rs.getString("legal_name"),
                        rs.getString("tax_identifier"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }
}
