package org.example.erp_module.controller;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;
final class WarehouseTestSupport {
 static void ensureSchema(JdbcTemplate db){try{if(Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('warehouse_config') IS NULL",Boolean.class)))db.execute(Files.readString(Path.of("src/main/resources/warehouse-basic-v1.sql")));}catch(Exception e){throw new IllegalStateException(e);}}
}
