package org.example.inventory_module;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public InventoryBootstrap(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        jdbc.execute("SELECT pg_advisory_xact_lock(7821347)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS erp_schema_versions (version varchar(100) PRIMARY KEY)");
        for (String[] migration : new String[][]{
                {"inventory-v1", "inventory-schema.sql"},
                {"items-v2", "items-v2.sql"},
                {"items-v3", "items-v3.sql"},
                {"items-v4", "items-v4.sql"},
                {"items-v5", "items-v5.sql"},
                {"items-v6", "items-v6.sql"},
                {"items-v7", "items-v7.sql"},
                {"sales-returns-v1", "sales-returns.sql"},
                {"sales-quotes-v1", "sales-quotes.sql"},
                {"sales-quotes-v2", "sales-quotes-v2.sql"},
                {"sales-documents-v3", "sales-documents-v3.sql"},
                {"sales-fields-v4", "sales-fields-v4.sql"},
                {"sales-lines-v5", "sales-lines-v5.sql"},
                {"sales-lines-v6", "sales-lines-v6.sql"},
                {"purchase-documents-v1", "purchase-documents.sql"},
                {"inventory-admin-delete-v1", "inventory-admin-delete.sql"},
                {"inventory-admin-delete-v2", "inventory-admin-delete-v2.sql"},
                {"inventory-admin-delete-v3", "inventory-admin-delete-v3.sql"},
                {"sales-quote-numbering-v1", "sales-quote-numbering.sql"},
                {"production-orders-v1", "production-orders.sql"},
                {"document-numbering-v1", "document-numbering.sql"},
                {"document-numbering-v2", "document-numbering-v2.sql"},
                {"sales-quote-renumbering-v1", "sales-quote-renumbering.sql"}}) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM erp_schema_versions WHERE version=? )", Boolean.class, migration[0]))) continue;
        try (var stream = new ClassPathResource(migration[1]).getInputStream()) {
            // Execute as one PostgreSQL script so dollar-quoted function bodies stay intact.
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        jdbc.update("INSERT INTO erp_schema_versions(version) VALUES (?)", migration[0]);
        }
    }
}
