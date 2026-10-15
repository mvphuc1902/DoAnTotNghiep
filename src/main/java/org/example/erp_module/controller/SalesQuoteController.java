package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.example.customer_module.entity.Customer;
import org.example.location_module.entity.Location;
import org.example.items_module.entity.Items;
import org.example.salesorder_module.entity.SalesOrder;
import org.example.salesorder_module.entity.SalesOrderLine;
import org.example.salesorder_module.service.impl.SalesOrderServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/erp/sales-quotes")
public class SalesQuoteController {
    private final JdbcTemplate jdbc;
    private final SalesOrderServiceImpl orders;
    public SalesQuoteController(JdbcTemplate jdbc, SalesOrderServiceImpl orders) {
        this.jdbc = jdbc; this.orders = orders;
    }

    @PostMapping
    @Transactional
    public Map<String, String> create(@RequestBody SalesOrder quote) {
        if (quote.getLines() == null || quote.getLines().isEmpty() || quote.getLines().size() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chứng từ bán cần từ 1 đến 500 dòng vật tư.");
        String documentType = quote.getDocumentType() == null || quote.getDocumentType().isBlank()
                ? "QUOTE" : quote.getDocumentType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("QUOTE", "ORDER").contains(documentType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại chứng từ bán không hợp lệ.");
        quote.setDocumentType(documentType);
        quote.setStatus("OPEN");
        SalesOrder document;
        if ("ORDER".equals(documentType)) {
            document = orders.create(quote);
        } else {
            quote.setOrderNo(jdbc.queryForObject("SELECT erp_next_document_no(?)", String.class, "SQ"));
            orders.prepare(quote);
            document = quote;
        }
        String status = "ORDER".equals(documentType) ? document.getStatus() : "DRAFT";
        Long id = jdbc.queryForObject("""
            INSERT INTO sales_headers(document_type,quote_no,customer_id,location_id,order_date,requested_delivery_date,
              currency_code,payment_terms_code,payment_method_code,salesperson_code,status,subtotal,discount_amount,vat_amount,total_amount,notes,sales_order_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
            """, Long.class, documentType, document.getOrderNo(), document.getCustomer().getId(),
                document.getLocation() == null ? null : document.getLocation().getId(), document.getOrderDate(),
                document.getRequestedDeliveryDate(), document.getCurrencyCode() == null ? "VND" : document.getCurrencyCode(),
                document.getPaymentTermsCode(), document.getPaymentMethodCode(), document.getSalespersonCode(),
                status, document.getSubtotal(), document.getDiscountAmount(), document.getVatAmount(),
                document.getTotalAmount(), document.getNotes(),
                "ORDER".equals(documentType) ? document.getId() : null);
        for (SalesOrderLine line : document.getLines()) jdbc.update("""
            INSERT INTO sales_quote_lines(sales_header_id,line_no,item_id,description,unit_of_measure,quantity,unit_price,discount_percent,vat_percent)
            VALUES(?,?,?,?,?,?,?,?,?)
            """, id,line.getLineNo(),line.getItem().getId(),line.getDescription(),line.getUnitOfMeasure(),
                line.getQuantity(),line.getUnitPrice(),line.getDiscountPercent(),line.getVatPercent());
        new SalesHeaderDetails(jdbc).save("sales_headers",id,quote.getDocumentDetails());
        return Map.of("id", id.toString(), "documentNo", document.getOrderNo());
    }

    @GetMapping("/{id}/lines")
    public List<Map<String, Object>> lines(@PathVariable long id) {
        List<Map<String, Object>> headers = jdbc.queryForList(
                "SELECT sales_order_id FROM sales_headers WHERE id=?", id);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chứng từ bán.");
        return jdbc.queryForList("""
            SELECT l.source_line_id AS id,l.id AS sales_line_id,l.source_table,l.line_no,l.item_id,i.item_no,
                   l.description,l.unit_of_measure,l.quantity::text AS quantity,l.unit_price::text AS unit_price,
                   l.discount_percent::text AS discount_percent,l.vat_percent::text AS vat_percent
            FROM sales_lines l LEFT JOIN items i ON i.id=l.item_id WHERE l.sales_header_id=? ORDER BY l.line_no,l.id
            """,id);
    }
    @PostMapping("/{id}/make-order")
    @Transactional
    public Map<String, String> makeOrder(@PathVariable long id) {
        var rows = jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy báo giá.");
        var header = rows.getFirst();
        if (!"QUOTE".equals(header.get("document_type")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ Sales Quotes mới có thể Make Order.");
        if (header.get("sales_order_id") != null) return Map.of("id", header.get("sales_order_id").toString());
        if (!Set.of("DRAFT","RELEASED").contains(header.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Báo giá không thể chuyển đơn.");
        var shortages = jdbc.queryForList("""
            SELECT i.item_no, w.code AS warehouse_code, requested.quantity AS requested_quantity,
                   coalesce(stock.available_quantity,0) AS available_quantity
            FROM (SELECT item_id,sum(quantity) AS quantity FROM sales_quote_lines
                  WHERE sales_header_id=? GROUP BY item_id) requested
            JOIN items i ON i.id=requested.item_id
            JOIN locations w ON w.id=coalesce(?,erp_default_location())
            LEFT JOIN inventory stock ON stock.item_id=requested.item_id AND stock.location_id=w.id
            WHERE i.item_category='INVENTORY' AND requested.quantity>coalesce(stock.available_quantity,0)
            ORDER BY i.item_no
            """, id, header.get("location_id"));
        if (!shortages.isEmpty()) {
            String details = shortages.stream().map(row -> row.get("item_no") + " tại kho " + row.get("warehouse_code")
                    + ": cần " + formatQuantity((BigDecimal) row.get("requested_quantity"))
                    + ", còn " + formatQuantity((BigDecimal) row.get("available_quantity")))
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không đủ tồn kho: " + details + ". Vui lòng nhập thêm hàng trước khi Make Order. Báo giá vẫn được giữ nguyên.");
        }
        SalesOrder order = new SalesOrder();
        order.setOrderNo(null);
        order.setOrderDate(java.time.LocalDate.now());
        order.setPostingDate(java.time.LocalDate.now());
        Customer customer = new Customer(); customer.setId(((Number)header.get("customer_id")).longValue()); order.setCustomer(customer);
        if (header.get("location_id") != null) {
            Location location = new Location(); location.setId(((Number)header.get("location_id")).longValue()); order.setLocation(location);
        }
        order.setCurrencyCode((String)header.get("currency_code"));
        order.setPaymentTermsCode((String)header.get("payment_terms_code"));
        order.setPaymentMethodCode((String)header.get("payment_method_code"));
        order.setSalespersonCode((String)header.get("salesperson_code"));
        order.setNotes((String)header.get("notes"));
        if (header.get("requested_delivery_date") instanceof Date date) order.setRequestedDeliveryDate(date.toLocalDate());
        for (var row : jdbc.queryForList("SELECT * FROM sales_quote_lines WHERE sales_header_id=? ORDER BY line_no", id)) {
            SalesOrderLine line = new SalesOrderLine();
            Items item = new Items(); item.setId(((Number)row.get("item_id")).longValue()); line.setItem(item);
            line.setLineNo(((Number)row.get("line_no")).intValue());
            line.setDescription((String)row.get("description")); line.setUnitOfMeasure((String)row.get("unit_of_measure"));
            line.setQuantity((BigDecimal)row.get("quantity")); line.setUnitPrice((BigDecimal)row.get("unit_price"));
            line.setDiscountPercent((BigDecimal)row.get("discount_percent")); line.setVatPercent((BigDecimal)row.get("vat_percent"));
            order.getLines().add(line);
        }
        if (order.getLines().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Báo giá chưa có dòng vật tư.");
        SalesOrder saved = orders.create(order);
        jdbc.update("""
            INSERT INTO sales_headers(document_type,quote_no,customer_id,location_id,order_date,requested_delivery_date,
              currency_code,payment_terms_code,payment_method_code,salesperson_code,status,subtotal,discount_amount,vat_amount,total_amount,notes,sales_order_id)
            SELECT 'ORDER',?,customer_id,location_id,?,requested_delivery_date,currency_code,payment_terms_code,
              payment_method_code,salesperson_code,'OPEN',subtotal,discount_amount,vat_amount,total_amount,notes,?
            FROM sales_headers WHERE id=?
            ON CONFLICT DO NOTHING
            """,saved.getOrderNo(),saved.getOrderDate(),saved.getId(),id);
        var targetHeaders = jdbc.queryForList("SELECT id FROM sales_headers WHERE sales_order_id=? AND document_type='ORDER'",Long.class,saved.getId());
        if (!targetHeaders.isEmpty()) new SalesHeaderDetails(jdbc).copy(id,targetHeaders.getFirst());
        jdbc.update("UPDATE sales_headers SET sales_order_id=?,status='CONVERTED',updated_at=CURRENT_TIMESTAMP WHERE id=?", saved.getId(), id);
        return Map.of("id", saved.getId().toString(), "documentNo", saved.getOrderNo(),
                "postingDate", saved.getPostingDate().toString());
    }

    private String formatQuantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    @PostMapping("/{id}/make-invoice")
    @Transactional
    public Map<String,String> makeInvoice(@PathVariable long id) {
        var header=jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE",id);
        if(header.isEmpty()||!"QUOTE".equals(header.getFirst().get("document_type")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Chỉ Sales Quote mới có thể Make Invoice.");
        var previous=jdbc.queryForList("SELECT sales_invoice_id FROM sales_quote_invoice_links WHERE sales_header_id=?",Long.class,id);
        if(!previous.isEmpty())return Map.of("id",previous.getFirst().toString());
        long orderId=Long.parseLong(makeOrder(id).get("id"));
        var lines=jdbc.queryForList("SELECT id,quantity,vat_percent FROM sales_order_lines WHERE sales_order_id=? ORDER BY line_no",orderId)
            .stream().map(line->new ErpWriteController.SalesDocumentLineInput(((Number)line.get("id")).longValue(),
                (BigDecimal)line.get("quantity"),(BigDecimal)line.get("vat_percent"))).toList();
        var result=new ErpWriteController(jdbc).createSalesInvoiceWithLines(new ErpWriteController.SalesDocumentCreateRequest(
            Map.of("invoice_no",jdbc.queryForObject("SELECT erp_next_document_no(?)", String.class, "S-INV"),"sales_order_id",orderId,"invoice_date",java.time.LocalDate.now().toString(),
                "posting_date",java.time.LocalDate.now().toString(),"currency_code",header.getFirst().get("currency_code")),lines));
        long invoiceId=Long.parseLong(result.get("id").toString());
        jdbc.update("INSERT INTO sales_quote_invoice_links(sales_header_id,sales_invoice_id) VALUES(?,?)",id,invoiceId);
        var details=new SalesHeaderDetails(jdbc);
        details.copy(id,details.headerId("sales_invoices",invoiceId));
        return Map.of("id",Long.toString(invoiceId));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> invalid(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                .body(Map.of("message", error.getReason() == null ? "Thông tin báo giá không hợp lệ." : error.getReason()));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> constraint(org.springframework.dao.DataIntegrityViolationException error) {
        String message = "Không lưu được chứng từ. Kiểm tra dữ liệu đã nhập.";
        if (error.getMostSpecificCause() instanceof java.sql.SQLException sql) {
            if ("23514".equals(sql.getSQLState()) && sql.getMessage() != null && sql.getMessage().contains("STOCK:")) {
                message = sql.getMessage().substring(sql.getMessage().indexOf("STOCK:") + 6).split("\\n")[0].trim()
                        + " Báo giá vẫn được giữ nguyên; kiểm tra kho trước khi Make Order lại.";
            } else if ("23505".equals(sql.getSQLState())) {
                message = "Mã chứng từ đã tồn tại. Vui lòng kiểm tra Document No.";
            } else if ("23503".equals(sql.getSQLState())) {
                message = "Khách hàng, vật tư hoặc kho liên quan không còn tồn tại. Vui lòng kiểm tra lại.";
            }
        }
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", message));
    }
}
