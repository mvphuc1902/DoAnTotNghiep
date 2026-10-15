package org.example.erp_module.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Extra document fields belong to the shared header, regardless of the legacy write adapter. */
final class SalesHeaderDetails {
    static final List<Map<String, Object>> FIELDS = readFields();
    private final JdbcTemplate jdbc;
    SalesHeaderDetails(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static List<Map<String, Object>> readFields() {
        try (var stream = new ClassPathResource("sales-form-fields.json").getInputStream()) {
            return new ObjectMapper().readValue(stream, new TypeReference<>() {});
        } catch (Exception error) { throw new IllegalStateException("Cannot load Sales field definitions", error); }
    }
    static List<String> names() { return FIELDS.stream().map(f -> f.get("name").toString()).toList(); }
    long headerId(String table, Object id) {
        String key = switch (table) {
            case "sales_headers" -> "id";
            case "sales_orders" -> "sales_order_id";
            case "sales_returns" -> "sales_return_id";
            case "sales_invoices" -> "sales_invoice_id";
            default -> throw bad("Không phải chứng từ Sales.");
        };
        String condition = table.equals("sales_orders") ? " AND document_type='ORDER'" : "";
        var ids = jdbc.queryForList("SELECT id FROM sales_headers WHERE " + key + "=?" + condition, Long.class, Long.valueOf(id.toString()));
        if (ids.size() != 1) throw bad("Không tìm thấy Sales Header tương ứng.");
        return ids.getFirst();
    }
    Map<String,Object> read(String table, Object id) {
        long header=headerId(table,id);
        var values=jdbc.queryForMap("SELECT " + String.join(",", names()) + " FROM sales_headers WHERE id=?", header);
        values.put("_header_id",Long.toString(header));
        values.put("_archived_versions",jdbc.queryForObject("SELECT count(*) FROM sales_document_archives WHERE sales_header_id=?",Integer.class,header));
        return values;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> extract(Map<String,Object> input) {
        Object value = input.remove("document_details");
        if (value == null) return Map.of();
        if (!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw bad("Thông tin Sales Header không hợp lệ.");
        return (Map<String,Object>) map;
    }
    void save(String table, Object id, Map<String,Object> values) {
        if (values == null || values.isEmpty()) return;
        long headerId = headerId(table,id);
        jdbc.queryForObject("SELECT id FROM sales_headers WHERE id=? FOR UPDATE", Long.class, headerId);
        Map<String,Object> converted = validate(values);
        var args = new ArrayList<>(converted.values()); args.add(headerId);
        jdbc.update("UPDATE sales_headers SET " + String.join(",", converted.keySet().stream().map(k -> k+"=?").toList()) + ",updated_at=CURRENT_TIMESTAMP WHERE id=?", args.toArray());
    }
    Map<String,Object> validate(Map<String,Object> values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (var entry: values.entrySet()) {
            var field = FIELDS.stream().filter(f -> f.get("name").equals(entry.getKey())).findFirst().orElseThrow(() -> bad("Trường Sales không hợp lệ: " + entry.getKey()));
            String value = entry.getValue()==null ? null : entry.getValue().toString().trim();
            if (value == null || value.isEmpty()) {
                if(entry.getKey().equals("currency_code"))throw bad("Currency Code là bắt buộc.");
                result.put(entry.getKey(),null); continue;
            }
            int limit = "textarea".equals(field.get("type")) ? 10000 : 255;
            if (value.length()>limit) throw bad(field.get("label")+" vượt độ dài cho phép.");
            if ("email".equals(field.get("type")) && !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw bad("Email không hợp lệ.");
            if (field.get("lookup")!=null) {
                String category=field.get("lookup").toString();
                boolean exists;
                if (category.equals("USER")) exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE username=?)",Boolean.class,value));
                else if(category.equals("PAYMENT_METHOD")) exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE payment_method=?)",Boolean.class,value));
                else if(category.equals("PAYMENT_TERMS")) exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customers WHERE payment_terms_code=? UNION ALL SELECT 1 FROM sales_setup_codes WHERE category='PAYMENT_TERMS' AND code=? AND active)",Boolean.class,value,value));
                else exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sales_setup_codes WHERE category=? AND code=? AND active)",Boolean.class,category,value));
                if (!exists) throw bad("Chọn giá trị có trong danh mục: "+field.get("label"));
            }
            try { result.put(entry.getKey(), "date".equals(field.get("type")) ? Date.valueOf(java.time.LocalDate.parse(value)) : value); }
            catch (IllegalArgumentException | java.time.DateTimeException error) { throw bad("Ngày không hợp lệ: "+field.get("label")); }
        }
        return result;
    }
    void copy(long source, long destination) {
        jdbc.update("UPDATE sales_headers target SET " + String.join(",",names().stream().map(k -> k+"=source."+k).toList()) + " FROM sales_headers source WHERE source.id=? AND target.id=?",source,destination);
    }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
