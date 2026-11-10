package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/erp")
@CrossOrigin(origins = "*", methods = RequestMethod.GET)
@Transactional(readOnly = true)
public class ErpDataController {
    private final JdbcTemplate jdbc;

    public ErpDataController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/purchase-items")
    public Map<String, Object> purchaseItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "") String search) {
        if (page < 0 || size < 1 || size > 100 || search.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số tra cứu Item không hợp lệ.");
        }
        String where = " WHERE NOT coalesce(item.blocked, false)";
        List<Object> parameters = new ArrayList<>();
        if (!search.isBlank()) {
            where += " AND strpos(lower(concat_ws(' ', item.item_no, item.description)), lower(?)) > 0";
            parameters.add(search.trim());
        }
        String from = " FROM public.items item";
        Long count = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT item.id, item.item_no, item.description, item.item_category, item.item_type,
                   item.base_unit_of_measure, item.unit_price, item.unit_cost, item.blocked
            """ + from + where + " ORDER BY item.item_no, item.id LIMIT ? OFFSET ?", parameters.toArray());
        normalizeRows(rows);
        return Map.of("rows", rows, "total", count, "page", page, "size", size);
    }

    @GetMapping("/production-order-items")
    public List<Map<String, Object>> productionOrderItems(
            @RequestParam(defaultValue = "") String search) {
        if (search.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Từ khóa tìm kiếm quá dài.");
        }
        List<Map<String, Object>> result = jdbc.queryForList("""
            SELECT id::text AS id, item_no, description, base_unit_of_measure, production_no
            FROM (
                SELECT item.id, item.item_no, item.description, item.base_unit_of_measure,
                       production.production_no,
                       row_number() OVER (
                           PARTITION BY item.id
                           ORDER BY production.created_at DESC NULLS LAST, production.id DESC
                       ) AS order_rank
                FROM public.production_orders production
                JOIN public.items item ON item.id = production.bom_parent_item_id
                WHERE item.item_type = 'MANUFACTURED'
                  AND item.item_category = 'INVENTORY'
                  AND NOT coalesce(item.blocked, false)
                  AND (? = '' OR strpos(lower(concat_ws(' ', item.item_no, item.description,
                      production.production_no)), lower(?)) > 0)
            ) candidates
            WHERE order_rank = 1
            ORDER BY item_no
            LIMIT 100
            """, search.trim(), search.trim());
        normalizeRows(result);
        return result;
    }

    @GetMapping("/payment-methods")
    public List<String> paymentMethods() {
        return jdbc.queryForList("SELECT DISTINCT payment_method FROM payments WHERE payment_method IS NOT NULL AND btrim(payment_method)<>'' ORDER BY payment_method", String.class);
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        List<Map<String, Object>> tables = new ArrayList<>();
        Set<String> installedTables = new HashSet<>(jdbc.queryForList("""
            SELECT c.relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
            """, String.class));
        ErpTables.COLUMNS.keySet().stream().filter(installedTables::contains).sorted().forEach(table -> {
            if (table.equals("users") && !isAdmin()) return;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", table);
            entry.put("columns", ErpTables.COLUMNS.get(table));
            entry.put("count", jdbc.queryForObject("SELECT count(*) FROM public." + quote(table), Long.class));
            tables.add(entry);
        });
        Map<String, Object> salesLines = new LinkedHashMap<>();
        salesLines.put("name", "sales_document_lines");
        salesLines.put("columns", ErpTables.COLUMNS.get("sales_document_lines"));
        salesLines.put("count", jdbc.queryForObject(salesDocumentLinesCountSql(), Long.class));
        tables.add(salesLines);
        tables.sort(Comparator.comparing(table -> table.get("name").toString()));
        List<Map<String, Object>> relations = jdbc.queryForList("""
            SELECT src.relname AS source_table, sa.attname AS source_column,
                   dst.relname AS target_table, da.attname AS target_column
            FROM pg_constraint c
            JOIN pg_class src ON src.oid = c.conrelid
            JOIN pg_namespace ns ON ns.oid = src.relnamespace
            JOIN pg_class dst ON dst.oid = c.confrelid
            JOIN pg_namespace nd ON nd.oid = dst.relnamespace
            JOIN pg_attribute sa ON sa.attrelid = src.oid AND sa.attnum = c.conkey[1]
            JOIN pg_attribute da ON da.attrelid = dst.oid AND da.attnum = c.confkey[1]
            WHERE c.contype = 'f' AND ns.nspname = 'public' AND nd.nspname = 'public'
              AND array_length(c.conkey, 1) = 1
            """);
        relations.removeIf(r -> !allowedColumn(r.get("source_table"), r.get("source_column"))
            || !allowedColumn(r.get("target_table"), r.get("target_column"))
            || (!isAdmin() && ("users".equals(r.get("source_table")) || "users".equals(r.get("target_table")))));
        return Map.of("tables", tables, "relations", relations);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return catalog(); }

    private boolean isAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
    }

    @GetMapping("/tables/{table}")
    public Map<String, Object> rows(@PathVariable String table,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String filterColumn,
            @RequestParam(required = false) String filterValue,
            @RequestParam(required = false) String documentType) {
        List<String> columns = columns(table);
        if (table.equals("sales_document_lines")) {
            if (page < 0 || size < 1 || size > 100 || search.length() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số phân trang không hợp lệ.");
            }
            if (documentType != null || (filterColumn != null && !columns.contains(filterColumn))
                    || (filterColumn != null && (filterValue == null || filterValue.length() > 255))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bộ lọc dòng Sales không hợp lệ.");
            }
            String from = " FROM (" + salesDocumentLinesSql() + ") sales_document_lines";
            String where = " WHERE 1=1";
            List<Object> parameters = new ArrayList<>();
            if (!search.isBlank()) {
                String term = search.trim();
                where += " AND (" + String.join(" OR ", columns.stream()
                        .map(column -> "strpos(lower(CAST(" + quote(column) + " AS text)),lower(?))>0").toList()) + ")";
                for (int i = 0; i < columns.size(); i++) parameters.add(term);
            }
            if (filterColumn != null) {
                where += " AND CAST(" + quote(filterColumn) + " AS text)=?";
                parameters.add(filterValue);
            }
            Long count = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, parameters.toArray());
            parameters.add(size);
            parameters.add((long) page * size);
            String selection = String.join(", ", columns.stream().map(ErpDataController::quote).toList());
            List<Map<String, Object>> result = jdbc.queryForList(
                    "SELECT " + selection + from + where + " ORDER BY document_date DESC, document_no, line_no LIMIT ? OFFSET ?",
                    parameters.toArray());
            normalizeRows(result);
            return Map.of("table", table, "columns", columns, "rows", result,
                    "total", count, "page", page, "size", size);
        }
        if (table.equals("inventory") || table.equals("inventory_ledger")) {
            columns = new ArrayList<>(columns);
            columns.remove("product_id");
            columns.addAll(List.of("item_code", "item_name", "location_name", "unit"));
        }
        if (table.equals("purchase_headers")) {
            columns = new ArrayList<>(columns);
            columns.addAll(List.of("vendor_name", "vendor_no"));
        } else if (table.equals("purchase_lines")) {
            columns = new ArrayList<>(columns);
            columns.add("item_no");
        } else if (table.equals("purchase_receipt_lines")) {
            columns = new ArrayList<>(columns);
            columns.addAll(List.of("item_no", "item_description"));
        } else if (table.equals("sales_headers")) {
            columns = new ArrayList<>(columns);
            columns.addAll(List.of("customer_no", "sell_to_name", "external_document_no",
                    "sell_to_contact", "due_date", "quote_valid_until_date",
                    "location_code", "assigned_user_id", "document_date"));
        } else if (table.equals("sales_order_lines")) {
            columns = new ArrayList<>(columns);
            columns.addAll(List.of("item_no", "item_category", "location_code"));
        }

        if (page < 0 || size < 1 || size > 100 || search.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số phân trang không hợp lệ.");
        }
        String where = " WHERE 1=1";
        List<Object> parameters = new ArrayList<>();
        if (!search.isBlank()) {
            List<String> predicates = new ArrayList<>();
            for (String column : columns) {
                predicates.add("strpos(lower(CAST(" + quote(column) + " AS text)), lower(?)) > 0");
                parameters.add(search.trim());
            }
            where += " AND (" + String.join(" OR ", predicates) + ")";
        }
        if (filterColumn != null) {
            if (!columns.contains(filterColumn) || filterValue == null || filterValue.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bộ lọc không hợp lệ.");
            }
            where += " AND CAST(" + quote(filterColumn) + " AS text) = ?";
            parameters.add(filterValue);
        }
        if (documentType != null) {
            boolean validSalesFilter = table.equals("sales_headers")
                    && Set.of("QUOTE", "ORDER", "RETURN_ORDER", "INVOICE").contains(documentType);
            boolean validPurchaseFilter = table.equals("purchase_lines") && documentType.equals("ORDER");
            if (!validSalesFilter && !validPurchaseFilter) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại chứng từ bán không hợp lệ.");
            }
            where += " AND \"document_type\" = ?";
            parameters.add(documentType);
        }
        boolean quoteList = table.equals("sales_headers")
                && ("QUOTE".equals(documentType)
                    || ("document_type".equals(filterColumn) && "QUOTE".equals(filterValue)));
        if (quoteList) {
            where += " AND \"status\" <> 'CONVERTED'";
        }
        String from = " FROM public." + quote(table);
        if (table.equals("purchase_headers")) {
            from = " FROM (SELECT header.*, vendor.name AS vendor_name, vendor.vendor_no AS vendor_no"
                + " FROM public.purchase_headers header JOIN public.vendors vendor ON vendor.id=header.vendor_id)"
                + " purchase_headers";
        } else if (table.equals("purchase_lines")) {
            from = " FROM (SELECT line.*, item.item_no FROM public.purchase_lines line"
                + " LEFT JOIN public.items item ON item.id=line.item_id) purchase_lines";
        } else if (table.equals("purchase_receipt_lines")) {
            from = " FROM (SELECT line.*, item.item_no, "
                + "coalesce(nullif(item.description,''), item.item_no) AS item_description "
                + "FROM public.purchase_receipt_lines line"
                + " LEFT JOIN public.items item ON item.id=line.item_id) purchase_receipt_lines";
        } else if (table.equals("sales_order_lines")) {
            from = " FROM (SELECT line.*, item.item_no, item.item_category, "
                + "nullif(location.code,'') AS location_code "
                + "FROM public.sales_order_lines line"
                + " LEFT JOIN public.items item ON item.id=line.item_id"
                + " LEFT JOIN public.sales_orders header ON header.id=line.sales_order_id"
                + " LEFT JOIN public.locations location ON location.id=header.location_id) sales_order_lines";
        } else if (table.equals("sales_headers")) {
            from = " FROM (SELECT header.*, customer.customer_no AS customer_no, "
                + "nullif(location.code,'') AS location_code"
                + " FROM public.sales_headers header"
                + " LEFT JOIN public.customers customer ON customer.id=header.customer_id"
                + " LEFT JOIN public.locations location ON location.id=header.location_id) sales_headers";
        }
        if (table.equals("inventory") || table.equals("inventory_ledger")) {
            from = " FROM (SELECT stock.*, COALESCE(item.item_no,legacy.code) AS item_code, "
                + "COALESCE(NULLIF(item.description,''),item.item_no,legacy.name,legacy.code) AS item_name, "
                + "location.name AS location_name, COALESCE(item.base_unit_of_measure,legacy.unit) AS unit "
                + "FROM public." + quote(table) + " stock LEFT JOIN items item ON item.id=stock.item_id "
                + "LEFT JOIN product legacy ON legacy.id=stock.product_id "
                + "LEFT JOIN locations location ON location.id=stock.location_id) inventory_view";
        }
        Long count = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        String selection = String.join(", ", columns.stream().map(ErpDataController::quote).toList());
        List<Map<String, Object>> result = jdbc.queryForList("SELECT " + selection + from + where
            + " ORDER BY id LIMIT ? OFFSET ?", parameters.toArray());
        normalizeRows(result);
        return Map.of("table", table, "columns", columns, "rows", result,
            "total", count, "page", page, "size", size);
    }

    public Map<String, Object> rows(String table, int page, int size, String search,
                                    String filterColumn, String filterValue) {
        return rows(table, page, size, search, filterColumn, filterValue, null);
    }

    private static boolean allowedColumn(Object table, Object column) {
        return ErpTables.COLUMNS.containsKey(table) && ErpTables.COLUMNS.get(table).contains(column);
    }

    private static List<String> columns(String table) {
        List<String> columns = ErpTables.COLUMNS.get(table);
        if (columns == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy danh mục.");
        return columns;
    }

    private static String quote(String identifier) { return "\"" + identifier + "\""; }

    private static void normalizeRows(List<Map<String, Object>> rows) {
        rows.forEach(row -> row.replaceAll((key, value) -> {
            if (value instanceof BigDecimal decimal) return decimal.toPlainString();
            if (value instanceof Long || value instanceof java.util.Date) return value.toString();
            return value;
        }));
    }

    private static String salesDocumentLinesCountSql() {
        return "SELECT count(*) FROM (" + salesDocumentLinesSql() + ") sales_document_lines";
    }

    private static String salesDocumentLinesSql() {
        return """
            SELECT concat(header.document_type,'-',header.id,'-',coalesce(line.id,0)) AS id,
              header.document_type,header.quote_no AS document_no,header.status,
              CASE WHEN line.id IS NULL THEN 'MISSING' ELSE 'HAS_LINES' END AS line_status,
              header.order_date AS document_date,customer.customer_no AS customer_no,
              coalesce(nullif(header.sell_to_name,''),customer.name) AS sell_to_name,
              CASE WHEN line.id IS NULL THEN NULL ELSE 'Item' END AS type,item.item_no,
              line.description,line.unit_of_measure,line.quantity,line.unit_price,line.discount_percent,
              line.discount_amount,line.vat_percent,line.vat_amount,line.line_amount,header.currency_code,
              greatest(coalesce(line.line_amount,0)-coalesce(line.vat_amount,0),0) AS line_amount_ex_vat,
              nullif(location.code,'') AS location_code,
              location.name AS location_name,
              header.shipment_date,
              greatest(coalesce(line.quantity,0)-coalesce(line.quantity_shipped,0),0) AS outstanding_quantity,
              related.order_no AS related_document_no,header.id AS document_id,
              line.line_no,line.source_table,line.source_line_id
            FROM sales_headers header
            LEFT JOIN sales_lines line ON line.sales_header_id=header.id
            LEFT JOIN customers customer ON customer.id=header.customer_id
            LEFT JOIN items item ON item.id=line.item_id
            LEFT JOIN locations location ON location.id=coalesce(line.location_id,header.location_id)
            LEFT JOIN sales_orders related ON related.id=header.related_sales_order_id
            """;
    }
}