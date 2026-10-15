package org.example.erp_module.controller;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp/sales-form")
public class SalesFormController {
    private final JdbcTemplate jdbc;
    public SalesFormController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostMapping("/headers/{id}/archive")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> archive(@PathVariable long id) {
        if(jdbc.queryForList("SELECT id FROM sales_headers WHERE id=? FOR UPDATE",Long.class,id).isEmpty())throw SalesHeaderDetails.bad("Không tìm thấy chứng từ.");
        Integer version=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM sales_document_archives WHERE sales_header_id=?",Integer.class,id);
        jdbc.update("""
            INSERT INTO sales_document_archives(sales_header_id,version,snapshot)
            SELECT h.id,?,jsonb_build_object('header',to_jsonb(h),'lines',coalesce(
              (SELECT jsonb_agg(to_jsonb(l) ORDER BY l.line_no,l.id) FROM sales_lines l WHERE l.sales_header_id=h.id),'[]'::jsonb))
            FROM sales_headers h WHERE h.id=?
            """,version,id);
        return Map.of("version",version);
    }
    @GetMapping("/headers/{id}/archives")
    public List<Map<String,Object>> archives(@PathVariable long id) {
        return jdbc.queryForList("SELECT version,created_at,snapshot::text AS snapshot FROM sales_document_archives WHERE sales_header_id=? ORDER BY version DESC",id);
    }
    @PostMapping("/headers/{id}/release")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> release(@PathVariable long id,@RequestParam(defaultValue="false") boolean reopen) {
        var header=jdbc.queryForMap("SELECT * FROM sales_headers WHERE id=? FOR UPDATE",id);
        String expected=reopen?"RELEASED":"DRAFT",target=reopen?"DRAFT":"RELEASED";
        if(!"QUOTE".equals(header.get("document_type"))||!expected.equals(header.get("status"))||header.get("sales_order_id")!=null)throw SalesHeaderDetails.bad("Chỉ Release/Reopen báo giá chưa chuyển đơn.");
        if(!reopen&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sales_lines WHERE sales_header_id=?)",Boolean.class,id)))throw SalesHeaderDetails.bad("Báo giá phải có dòng hàng.");
        jdbc.update("UPDATE sales_headers SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",target,id);
        return Map.of("status",target);
    }
    @GetMapping("/fields")
    public List<Map<String,Object>> fields() { return SalesHeaderDetails.FIELDS; }
    @GetMapping("/values")
    public Map<String,Object> values(@RequestParam String table,@RequestParam long id) {
        return new SalesHeaderDetails(jdbc).read(table,id);
    }
    @GetMapping("/options/{category}")
    public List<Map<String,Object>> options(@PathVariable String category) {
        if (category.equals("USER")) return jdbc.queryForList("SELECT username AS code,coalesce(name,username) AS name FROM users ORDER BY username");
        if (category.equals("PAYMENT_METHOD")) return jdbc.queryForList("SELECT DISTINCT payment_method AS code,payment_method AS name FROM payments WHERE payment_method IS NOT NULL AND btrim(payment_method)<>'' ORDER BY code");
        if (category.equals("SALESPERSON")) return jdbc.queryForList("SELECT employee_code AS code,name FROM employees WHERE active ORDER BY employee_code");
        if (category.equals("PAYMENT_TERMS")) return jdbc.queryForList("SELECT code,name FROM sales_setup_codes WHERE category='PAYMENT_TERMS' AND active UNION SELECT DISTINCT payment_terms_code,payment_terms_code FROM customers WHERE payment_terms_code IS NOT NULL AND btrim(payment_terms_code)<>'' ORDER BY code");
        return jdbc.queryForList("SELECT code,name FROM sales_setup_codes WHERE category=? AND active ORDER BY code",category);
    }
}
