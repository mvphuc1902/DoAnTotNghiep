package org.example.erp_module.controller;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    @DeleteMapping("/headers/{id}/orphaned-invoice")
    @org.springframework.transaction.annotation.Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrphanedInvoice(@PathVariable long id) {
        var rows=jdbc.queryForList("SELECT sales_invoice_id FROM sales_headers WHERE id=? AND document_type='INVOICE' FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy Sales Invoice.");
        Object invoiceId=rows.getFirst().get("sales_invoice_id");
        if(invoiceId==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Sales Invoice không có liên kết chứng từ nguồn.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sales_invoices WHERE id=?)",Boolean.class,invoiceId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Chứng từ nguồn vẫn còn. Hãy xóa Sales Invoice từ biểu mẫu.");
        jdbc.update("DELETE FROM sales_quote_invoice_links WHERE sales_invoice_id=?",invoiceId);
        jdbc.update("DELETE FROM sales_document_archives WHERE sales_header_id=?",id);
        for(var reference:jdbc.queryForList("""
            SELECT s.relname AS child_table,a.attname AS child_column
            FROM pg_constraint c JOIN pg_class s ON s.oid=c.conrelid
            JOIN pg_namespace ns ON ns.oid=s.relnamespace
            JOIN pg_class p ON p.oid=c.confrelid JOIN pg_namespace np ON np.oid=p.relnamespace
            JOIN pg_attribute a ON a.attrelid=s.oid AND a.attnum=c.conkey[1]
            WHERE c.contype='f' AND ns.nspname='public' AND np.nspname='public'
              AND p.relname='sales_headers'
            """)) {
            String child=reference.get("child_table").toString(),column=reference.get("child_column").toString();
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public."+quote(child)+" WHERE "+quote(column)+"=?)",Boolean.class,id)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Hóa đơn đang được sử dụng trong "+child+".");
        }
        if(jdbc.update("DELETE FROM sales_headers WHERE id=? AND document_type='INVOICE'",id)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Không thể xóa Sales Invoice.");
    }
    private static String quote(String identifier) { return "\""+identifier.replace("\"","\"\"")+"\""; }
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
    @GetMapping("/order-items")
    public List<Map<String,Object>> orderItems(@RequestParam long orderId,
            @RequestParam(defaultValue="") String search,
            @RequestParam(required=false) String category) {
        if(orderId<=0||search.length()>200)throw SalesHeaderDetails.bad("Bộ lọc Item của Sales Order không hợp lệ.");
        if(category!=null&&!Set.of("INVENTORY","SERVICE","NON_INVENTORY").contains(category))
            throw SalesHeaderDetails.bad("Loại Item không hợp lệ.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sales_orders WHERE id=?)",Boolean.class,orderId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sales Order không còn tồn tại.");
        StringBuilder sql=new StringBuilder("""
            SELECT line.id,line.line_no,line.sales_order_id,line.item_id,line.description,
              line.unit_of_measure,line.quantity,line.quantity_invoiced,line.quantity_shipped,
              line.unit_price,line.discount_percent,line.vat_percent,line.line_amount,
              item.item_no,item.description AS item_description,item.item_category,
              nullif(location.code,'') AS location_code
            FROM sales_order_lines line
            JOIN items item ON item.id=line.item_id
            LEFT JOIN sales_orders header ON header.id=line.sales_order_id
            LEFT JOIN locations location ON location.id=header.location_id
            WHERE line.sales_order_id=?
            """);
        List<Object> args=new ArrayList<>(List.of(orderId));
        if(category!=null){sql.append(" AND item.item_category=?");args.add(category);}
        if(!search.isBlank()){
            sql.append(" AND (strpos(lower(coalesce(item.item_no,'')),lower(?))>0")
                .append(" OR strpos(lower(coalesce(item.description,'')),lower(?))>0")
                .append(" OR strpos(lower(coalesce(line.description,'')),lower(?))>0)");
            String term=search.trim();args.add(term);args.add(term);args.add(term);
        }
        sql.append(" ORDER BY item.item_no,line.line_no");
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList(sql.toString(),args.toArray())){
            Map<String,Object> line=new LinkedHashMap<>(row);
            for(String id:List.of("id","sales_order_id","item_id"))
                if(line.get(id)!=null)line.put(id,line.get(id).toString());
            result.add(line);
        }
        return result;
    }
}
