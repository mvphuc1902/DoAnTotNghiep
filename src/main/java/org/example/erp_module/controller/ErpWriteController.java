package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/erp")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
public class ErpWriteController {
    private final JdbcTemplate jdbc;
    public ErpWriteController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record BomLine(long componentId, BigDecimal quantity) {}
    public record BomDocument(List<BomLine> expected, List<BomLine> lines) {}
    public record SalesDocumentLineInput(Long salesOrderLineId, BigDecimal quantity, BigDecimal vatPercent) {}
    public record SalesDocumentCreateRequest(Map<String, Object> header, List<SalesDocumentLineInput> lines) {}
    public record PurchaseDocumentLineInput(Long receiptLineId, Long itemId, String description,
            String unitOfMeasure, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal discountPercent, BigDecimal vatPercent, String locationCode) {}
    public record PurchaseDocumentCreateRequest(Map<String, Object> header, List<PurchaseDocumentLineInput> lines) {}

    @GetMapping("/purchase-receipt-lines/available")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availablePurchaseReceiptLines(
            @RequestParam(required = false) Long vendorId,
            @RequestParam(defaultValue = "") String search) {
        if (vendorId != null && vendorId <= 0) throw bad("Nhà cung cấp không hợp lệ.");
        if (search.length() > 200) throw bad("Từ khóa tìm kiếm quá dài.");
        return jdbc.queryForList("""
            WITH receipt_base AS (
                SELECT rl.*, sum(rl.quantity) OVER (
                    PARTITION BY rl.purchase_order_line_id ORDER BY rl.id
                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                ) AS prior_receipt_quantity
                FROM purchase_receipt_lines rl
            ), receipt_usage AS (
                SELECT base.*,
                    coalesce((SELECT sum(pl.quantity) FROM purchase_lines pl
                              WHERE pl.document_type='INVOICE'
                                AND pl.source_purchase_line_id=base.purchase_order_line_id
                                AND pl.purchase_receipt_line_id IS NULL),0) AS legacy_invoiced,
                    coalesce((SELECT sum(pl.quantity) FROM purchase_lines pl
                              WHERE pl.document_type='INVOICE'
                                AND pl.purchase_receipt_line_id=base.id),0) AS line_invoiced
                FROM receipt_base base
            )
            SELECT usage.id AS receipt_line_id, r.receipt_no, r.vendor_id, v.name AS vendor_name,
                   usage.item_id, i.item_no,
                   coalesce(nullif(pol.description, ''), i.description, i.item_no) AS description,
                   coalesce(nullif(usage.unit_of_measure, ''), pol.unit_of_measure, i.base_unit_of_measure) AS unit_of_measure,
                   usage.quantity AS received_quantity,
                   coalesce(usage.line_invoiced,0) +
                     least(usage.quantity, greatest(usage.legacy_invoiced-coalesce(usage.prior_receipt_quantity,0),0)) AS invoiced_quantity,
                   greatest(usage.quantity-coalesce(usage.line_invoiced,0)-
                     least(usage.quantity, greatest(usage.legacy_invoiced-coalesce(usage.prior_receipt_quantity,0),0)),0) AS remaining_quantity,
                   coalesce(pol.unit_price, i.unit_cost, 0) AS unit_price,
                   coalesce(pol.discount_percent, 0) AS discount_percent,
                   coalesce(pol.vat_percent, 0) AS vat_percent, usage.location_code
            FROM receipt_usage usage
            JOIN purchase_receipts r ON r.id=usage.receipt_id
            JOIN vendors v ON v.id=r.vendor_id
            LEFT JOIN items i ON i.id=usage.item_id
            LEFT JOIN purchase_lines pol ON pol.id=usage.purchase_order_line_id AND pol.document_type='ORDER'
            WHERE (? IS NULL OR r.vendor_id=?)
              AND greatest(usage.quantity-coalesce(usage.line_invoiced,0)-
                     least(usage.quantity, greatest(usage.legacy_invoiced-coalesce(usage.prior_receipt_quantity,0),0)),0)>0
              AND (?='' OR strpos(lower(concat_ws(' ',r.receipt_no,i.item_no,i.description)),lower(?))>0)
            ORDER BY r.receipt_date DESC, r.receipt_no, usage.line_no
            LIMIT 500
            """, vendorId, vendorId, search.trim(), search.trim());
    }

    @PostMapping("/purchase-documents/with-lines")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createPurchaseDocument(@RequestBody PurchaseDocumentCreateRequest request) {
        if (request == null || request.header() == null) throw bad("Thiếu thông tin chứng từ mua.");
        List<PurchaseDocumentLineInput> lines = validatedPurchaseLines(request.lines());
        Map<String, Object> input = request.header();
        String type = purchaseText(input.get("document_type"), "Loại chứng từ");
        if (!Set.of("ORDER", "QUOTE", "INVOICE").contains(type))
            throw bad("Loại chứng từ phải là ORDER, QUOTE hoặc INVOICE.");
        long vendorId = purchaseLong(input.get("vendor_id"), "Nhà cung cấp");
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM vendors WHERE id=? AND coalesce(blocked,false)=false)", Boolean.class, vendorId)))
            throw bad("Nhà cung cấp không tồn tại hoặc đang bị khóa.");
        java.sql.Date orderDate = purchaseDate(input.get("order_date"), "Ngày chứng từ", true);
        java.sql.Date postingDate = purchaseDate(input.get("posting_date"), "Posting Date", false);
        java.sql.Date requestedDate = purchaseDate(input.get("requested_receipt_date"), "Ngày nhận hàng yêu cầu", false);
        java.sql.Date promisedDate = purchaseDate(input.get("promised_receipt_date"), "Ngày nhận hàng dự kiến", false);
        String currency = purchaseOptionalText(input.get("currency_code"), "Tiền tệ", 10);
        if (currency == null) currency = "VND";
        String vendorReference = purchaseOptionalText(input.get("vendor_reference"), "Mã tham chiếu nhà cung cấp", 100);
        String externalDocumentNo = purchaseOptionalText(input.get("external_document_no"), "Số chứng từ ngoài", 100);
        String paymentTerms = purchaseOptionalText(input.get("payment_terms_code"), "Điều khoản thanh toán", 20);
        String shipmentMethod = purchaseOptionalText(input.get("shipment_method_code"), "Phương thức giao hàng", 20);
        String salesperson = purchaseOptionalText(input.get("salesperson_code"), "Mã nhân viên phụ trách", 30);
        String location = purchaseOptionalText(input.get("location_code"), "Mã kho", 20);
        validatePurchaseLocation(location);
        String notes = purchaseOptionalText(input.get("notes"), "Ghi chú", 10000);
        BigDecimal headerDiscount = purchasePercent(input.get("discount_percent"), "Chiết khấu chứng từ");
        String numberPrefix = switch (type) { case "ORDER" -> "PO"; case "QUOTE" -> "PQ"; default -> "P-INV"; };
        String documentNo = jdbc.queryForObject("SELECT erp_next_document_no(?)", String.class, numberPrefix);
        Long headerId = jdbc.queryForObject("""
            INSERT INTO purchase_headers(document_type,document_no,vendor_id,order_date,posting_date,
                requested_receipt_date,promised_receipt_date,vendor_reference,external_document_no,
                currency_code,payment_terms_code,shipment_method_code,salesperson_code,location_code,
                discount_percent,notes)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
            """, Long.class, type, documentNo, vendorId, orderDate, postingDate, requestedDate,
                promisedDate, vendorReference, externalDocumentNo, currency, paymentTerms, shipmentMethod,
                salesperson, location, headerDiscount, notes);

        BigDecimal discounts = BigDecimal.ZERO, taxes = BigDecimal.ZERO, total = BigDecimal.ZERO;
        int lineNo = 10000;
        for (PurchaseDocumentLineInput line : lines) {
            long itemId;
            String description;
            String unit;
            String lineLocation;
            BigDecimal price;
            BigDecimal discountPercent = purchasePercent(line.discountPercent(), "Chiết khấu dòng");
            BigDecimal vatPercent = purchasePercent(line.vatPercent(), "VAT dòng");
            Long receiptLineId = line.receiptLineId();
            Long sourcePurchaseLineId = null;
            if ("INVOICE".equals(type)) {
                if (receiptLineId == null || line.itemId() != null)
                    throw bad("Purchase Invoice chỉ nhận dòng được chọn từ Purchase Receipt.");
                Map<String, Object> source = purchaseReceiptLine(receiptLineId, vendorId);
                BigDecimal alreadyInvoiced = Objects.requireNonNullElse(jdbc.queryForObject("""
                    SELECT sum(quantity) FROM purchase_lines
                    WHERE purchase_receipt_line_id=? AND document_type='INVOICE'
                    """, BigDecimal.class, receiptLineId), BigDecimal.ZERO);
                BigDecimal quantity = (BigDecimal) source.get("quantity");
                BigDecimal legacyInvoiced = (BigDecimal) source.get("legacy_invoiced");
                BigDecimal priorReceipts = (BigDecimal) source.get("prior_receipt_quantity");
                BigDecimal legacyAllocated = legacyInvoiced.subtract(priorReceipts).max(BigDecimal.ZERO).min(quantity);
                BigDecimal remaining = quantity.subtract(legacyAllocated).subtract(alreadyInvoiced);
                if (line.quantity().compareTo(remaining) > 0)
                    throw bad("Số lượng hóa đơn vượt số lượng còn lại trên phiếu nhập " + source.get("receipt_no") + ".");
                itemId = ((Number) source.get("item_id")).longValue();
                sourcePurchaseLineId = source.get("purchase_order_line_id") == null
                        ? null : ((Number) source.get("purchase_order_line_id")).longValue();
                description = String.valueOf(source.get("description"));
                unit = (String) source.get("unit_of_measure");
                lineLocation = (String) source.get("location_code");
                String requestedLocation = purchaseOptionalText(line.locationCode(), "Mã kho dòng", 20);
                if (requestedLocation != null && !requestedLocation.equals(lineLocation))
                    throw bad("Kho dòng hóa đơn phải khớp kho trên Purchase Receipt.");
                price = line.unitPrice();
                if (line.vatPercent() == null) vatPercent = (BigDecimal) source.get("vat_percent");
            } else {
                if (receiptLineId != null || line.itemId() == null || line.itemId() <= 0)
                    throw bad("Chọn mặt hàng hợp lệ cho Purchase Order hoặc Purchase Quote.");
                List<Map<String, Object>> itemRows = jdbc.queryForList("""
                    SELECT item_no, description, base_unit_of_measure FROM items
                    WHERE id=? AND coalesce(blocked,false)=false
                    """, line.itemId());
                if (itemRows.isEmpty()) throw bad("Mặt hàng không tồn tại hoặc đang bị khóa.");
                Map<String, Object> item = itemRows.getFirst();
                itemId = line.itemId();
                description = purchaseOptionalText(line.description(), "Mô tả dòng", 250);
                if (description == null) description = Objects.toString(item.get("description"), Objects.toString(item.get("item_no"), ""));
                unit = purchaseOptionalText(line.unitOfMeasure(), "Đơn vị tính", 20);
                if (unit == null) unit = (String) item.get("base_unit_of_measure");
                if (line.unitOfMeasure() != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM units_of_measure WHERE code=? AND active=true)", Boolean.class, unit)))
                    throw bad("Đơn vị không tồn tại hoặc đã ngừng sử dụng trong Unit: " + unit);
                lineLocation = purchaseOptionalText(line.locationCode(), "Mã kho dòng", 20);
                if (lineLocation == null) lineLocation = location;
                validatePurchaseLocation(lineLocation);
                price = line.unitPrice();
            }
            BigDecimal gross = line.quantity().multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal lineDiscount = gross.multiply(discountPercent).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal base = gross.subtract(lineDiscount);
            BigDecimal tax = base.multiply(vatPercent).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal lineTotal = base.add(tax);
            jdbc.update("""
                INSERT INTO purchase_lines(purchase_header_id,document_type,document_no,line_no,item_id,
                    description,location_code,unit_of_measure,quantity,quantity_received,quantity_invoiced,
                    unit_price,discount_percent,discount_amount,vat_percent,vat_amount,line_amount,
                    source_purchase_line_id,purchase_receipt_line_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, headerId, type, documentNo, lineNo, itemId, description, lineLocation, unit,
                    line.quantity(), BigDecimal.ZERO, "INVOICE".equals(type) ? line.quantity() : BigDecimal.ZERO,
                    price, discountPercent, lineDiscount, vatPercent, tax, lineTotal,
                    sourcePurchaseLineId, receiptLineId);
            discounts = discounts.add(lineDiscount);
            taxes = taxes.add(tax);
            total = total.add(lineTotal);
            lineNo += 10000;
        }
        BigDecimal net = total.subtract(taxes);
        BigDecimal documentDiscount = net.multiply(headerDiscount).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal documentTaxReduction = net.signum() == 0 ? BigDecimal.ZERO
                : taxes.multiply(documentDiscount).divide(net, 2, java.math.RoundingMode.HALF_UP);
        taxes = taxes.subtract(documentTaxReduction);
        jdbc.update("""
            UPDATE purchase_headers SET discount_amount=?,vat_amount=?,total_amount=?,updated_at=CURRENT_TIMESTAMP
            WHERE id=?
            """, discounts.add(documentDiscount), taxes, net.subtract(documentDiscount).add(taxes), headerId);
        return Map.of("id", String.valueOf(headerId), "documentNo", documentNo);
    }

    private Map<String, Object> purchaseReceiptLine(long receiptLineId, long vendorId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT rl.quantity, rl.item_id, rl.purchase_order_line_id, r.receipt_no, r.vendor_id,
                   coalesce(nullif(pol.description,''), i.description, i.item_no) AS description,
                   coalesce(nullif(rl.unit_of_measure,''), pol.unit_of_measure, i.base_unit_of_measure) AS unit_of_measure,
                   coalesce(pol.vat_percent,0) AS vat_percent, rl.location_code,
                   coalesce((SELECT sum(pl.quantity) FROM purchase_lines pl
                             WHERE pl.document_type='INVOICE'
                               AND pl.source_purchase_line_id=rl.purchase_order_line_id
                               AND pl.purchase_receipt_line_id IS NULL),0) AS legacy_invoiced,
                   coalesce((SELECT sum(previous.quantity) FROM purchase_receipt_lines previous
                             WHERE previous.purchase_order_line_id=rl.purchase_order_line_id
                              AND previous.id<rl.id),0) AS prior_receipt_quantity,
                   coalesce((SELECT sum(pl.quantity) FROM purchase_lines pl
                            WHERE pl.document_type='INVOICE' AND pl.purchase_receipt_line_id=rl.id),0) AS line_invoiced
            FROM purchase_receipt_lines rl
            JOIN purchase_receipts r ON r.id=rl.receipt_id
            LEFT JOIN items i ON i.id=rl.item_id
            LEFT JOIN purchase_lines pol ON pol.id=rl.purchase_order_line_id AND pol.document_type='ORDER'
            WHERE rl.id=? FOR UPDATE OF rl
            """, receiptLineId);
        if (rows.isEmpty()) throw bad("Dòng Purchase Receipt đã chọn không còn tồn tại.");
        Map<String, Object> source = rows.getFirst();
        if (((Number) source.get("vendor_id")).longValue() != vendorId)
            throw bad("Nhà cung cấp trên hóa đơn phải khớp với phiếu nhập đã chọn.");
        return source;
    }

    private void validatePurchaseLocation(String code) {
        if (code != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM locations WHERE code=?)", Boolean.class, code)))
            throw bad("Kho không tồn tại trong Locations: " + code);
    }

    private static List<PurchaseDocumentLineInput> validatedPurchaseLines(List<PurchaseDocumentLineInput> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 500)
            throw bad("Thêm ít nhất một dòng hàng (tối đa 500 dòng).");
        Set<Long> receiptLines = new HashSet<>();
        for (PurchaseDocumentLineInput line : lines) {
            if (line == null || line.quantity() == null || line.quantity().signum() <= 0
                    || line.quantity().stripTrailingZeros().scale() > 3
                    || line.quantity().compareTo(new BigDecimal("999999999999999.999")) > 0
                    || line.unitPrice() == null || line.unitPrice().signum() < 0
                    || line.unitPrice().scale() > 2)
                throw bad("Số lượng phải lớn hơn 0 (tối đa 3 số lẻ) và đơn giá hợp lệ (tối đa 2 số lẻ).");
            purchasePercent(line.discountPercent(), "Chiết khấu dòng");
            purchasePercent(line.vatPercent(), "VAT dòng");
            if (line.receiptLineId() != null && !receiptLines.add(line.receiptLineId()))
                throw bad("Không thể chọn trùng dòng Purchase Receipt.");
        }
        return lines;
    }

    private static BigDecimal purchasePercent(BigDecimal value, String label) {
        BigDecimal percent = Objects.requireNonNullElse(value, BigDecimal.ZERO);
        if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0
                || percent.stripTrailingZeros().scale() > 2) throw bad(label + " phải từ 0 đến 100.");
        return percent;
    }

    private static BigDecimal purchasePercent(Object value, String label) {
        if (value == null || value.toString().isBlank()) return BigDecimal.ZERO;
        try { return purchasePercent(new BigDecimal(value.toString().trim()), label); }
        catch (NumberFormatException error) { throw bad(label + " không hợp lệ."); }
    }

    private static String purchaseText(Object value, String label) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) throw bad("Vui lòng nhập " + label + ".");
        return text;
    }

    private static String purchaseOptionalText(Object value, String label, int maxLength) {
        String text = value == null ? null : value.toString().trim();
        if (text != null && text.isEmpty()) return null;
        if (text != null && text.length() > maxLength) throw bad(label + " vượt độ dài cho phép.");
        return text;
    }

    private static long purchaseLong(Object value, String label) {
        try { long parsed = Long.parseLong(purchaseText(value, label)); if (parsed > 0) return parsed; }
        catch (NumberFormatException ignored) { }
        throw bad(label + " không hợp lệ.");
    }

    private static java.sql.Date purchaseDate(Object value, String label, boolean required) {
        if (value == null || value.toString().isBlank()) {
            if (required) throw bad("Vui lòng nhập " + label + ".");
            return null;
        }
        try { return java.sql.Date.valueOf(value.toString().trim()); }
        catch (IllegalArgumentException ignored) { throw bad(label + " không hợp lệ."); }
    }

    @PostMapping("/sales-invoices/with-lines")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createSalesInvoiceWithLines(@RequestBody SalesDocumentCreateRequest request) {
        List<SalesDocumentLineInput> lines = validatedSalesLines(request, true);
        Map<String, Object> invoiceInput = new LinkedHashMap<>(request.header());
        Map<String, Object> details = SalesHeaderDetails.extract(invoiceInput);
        long orderId = requiredOrderId(invoiceInput);
        List<Map<String, Object>> orderRows = jdbc.queryForList(
                "SELECT customer_id FROM sales_orders WHERE id=? FOR UPDATE", orderId);
        if (orderRows.isEmpty()) throw bad("Sales Order đã chọn không tồn tại.");
        String orderCustomerId = String.valueOf(orderRows.getFirst().get("customer_id"));
        Object suppliedCustomer = invoiceInput.get("customer_id");
        if (suppliedCustomer != null && !orderCustomerId.equals(suppliedCustomer.toString()))
            throw bad("Khách hàng trên hóa đơn phải khớp với Sales Order.");
        invoiceInput.put("customer_id", orderCustomerId);
        autoDocumentNumber("sales_invoices", invoiceInput);
        Map<String, Object> header = validate("sales_invoices", invoiceInput, true);
        Object invoiceId = insertValidated("sales_invoices", header);
        for (SalesDocumentLineInput line : lines) {
            List<Map<String, Object>> sourceRows = jdbc.queryForList("""
                SELECT id,line_no,item_id,description,quantity,unit_price,discount_percent,vat_percent
                FROM sales_order_lines WHERE id=? AND sales_order_id=? FOR UPDATE
                """, line.salesOrderLineId(), orderId);
            if (sourceRows.isEmpty()) throw bad("Dòng hóa đơn phải thuộc Sales Order đã chọn.");
            Map<String, Object> source = sourceRows.getFirst();
            Long itemId = ((Number) source.get("item_id")).longValue();
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM items WHERE id=?)", Boolean.class, itemId)))
                throw conflict("Mặt hàng của dòng Sales Order không còn tồn tại trong Items.");
            BigDecimal alreadyInvoiced = jdbc.queryForObject(
                    "SELECT coalesce(sum(quantity),0) FROM sales_invoice_lines WHERE sales_order_line_id=?",
                    BigDecimal.class, line.salesOrderLineId());
            BigDecimal quantity = line.quantity();
            if (quantity.add(alreadyInvoiced).compareTo((BigDecimal) source.get("quantity")) > 0)
                throw bad("Số lượng xuất hóa đơn vượt số lượng còn lại của dòng Sales Order.");
            BigDecimal price = (BigDecimal) source.get("unit_price");
            BigDecimal discount = Objects.requireNonNullElse((BigDecimal) source.get("discount_percent"), BigDecimal.ZERO);
            BigDecimal amount = quantity.multiply(price)
                    .multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2)))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal tax = amount.multiply(line.vatPercent().movePointLeft(2))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            jdbc.update("""
                INSERT INTO sales_invoice_lines(invoice_id,line_no,sales_order_line_id,item_id,description,
                    quantity,unit_price,vat_percent,vat_amount,line_amount)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, invoiceId, source.get("line_no"), line.salesOrderLineId(), itemId,
                    source.get("description"), quantity, price, line.vatPercent(), tax, amount);
        }
        new SalesHeaderDetails(jdbc).save("sales_invoices",invoiceId,details);
        return Map.of("id", String.valueOf(invoiceId));
    }

    @PostMapping("/sales-returns/with-lines")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createSalesReturnWithLines(@RequestBody SalesDocumentCreateRequest request) {
        List<SalesDocumentLineInput> lines = validatedSalesLines(request, false);
        Map<String, Object> returnInput = new LinkedHashMap<>(request.header());
        Map<String, Object> details = SalesHeaderDetails.extract(returnInput);
        autoDocumentNumber("sales_returns", returnInput);
        Map<String, Object> header = validate("sales_returns", returnInput, true);
        long orderId = requiredOrderId(header);
        Object returnId = insertValidated("sales_returns", header);
        for (SalesDocumentLineInput line : lines) {
            List<Map<String, Object>> sourceRows = jdbc.queryForList("""
                SELECT id,item_id,quantity FROM sales_order_lines
                WHERE id=? AND sales_order_id=? FOR UPDATE
                """, line.salesOrderLineId(), orderId);
            if (sourceRows.isEmpty()) throw bad("Dòng trả hàng phải thuộc Sales Order đã chọn.");
            Map<String, Object> source = sourceRows.getFirst();
            Long itemId = ((Number) source.get("item_id")).longValue();
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM items WHERE id=?)", Boolean.class, itemId)))
                throw conflict("Mặt hàng của dòng Sales Order không còn tồn tại trong Items.");
            jdbc.update("""
                INSERT INTO sales_return_lines(return_id,sales_order_line_id,item_id,quantity)
                VALUES(?,?,?,?)
                """, returnId, line.salesOrderLineId(), itemId, line.quantity());
        }
        new SalesHeaderDetails(jdbc).save("sales_returns",returnId,details);
        return Map.of("id", String.valueOf(returnId));
    }

    private static List<SalesDocumentLineInput> validatedSalesLines(
            SalesDocumentCreateRequest request, boolean invoice) {
        if (request == null || request.header() == null) throw bad("Thiếu thông tin chứng từ.");
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 500)
            throw bad("Chọn ít nhất một dòng Sales Order (tối đa 500 dòng).");
        Set<Long> sourceLines = new HashSet<>();
        for (SalesDocumentLineInput line : request.lines()) {
            validateSalesLine(line, invoice);
            if (!sourceLines.add(line.salesOrderLineId())) throw bad("Không thể chọn trùng dòng Sales Order.");
        }
        return request.lines();
    }

    private static void validateSalesLine(SalesDocumentLineInput line, boolean invoice) {
        if (line == null || line.salesOrderLineId() == null || line.salesOrderLineId() <= 0
                || line.quantity() == null || line.quantity().signum() <= 0
                || line.quantity().stripTrailingZeros().scale() > 3
                || line.quantity().compareTo(new BigDecimal("999999999999999.999")) > 0)
            throw bad("Số lượng trên dòng phải lớn hơn 0 và tối đa 3 chữ số thập phân.");
        if (invoice && (line.vatPercent() == null || line.vatPercent().signum() < 0
                || line.vatPercent().compareTo(new BigDecimal("100")) > 0
                || line.vatPercent().stripTrailingZeros().scale() > 2))
            throw bad("Thuế suất VAT phải từ 0 đến 100, tối đa 2 chữ số thập phân.");
    }

    private static long requiredOrderId(Map<String, Object> values) {
        Object value = values.get("sales_order_id");
        try {
            long id = value instanceof Number number ? number.longValue() : Long.parseLong(Objects.toString(value, ""));
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            throw bad("Chọn mã Sales Order.");
        }
        throw bad("Chọn mã Sales Order.");
    }

    private Object insertValidated(String table, Map<String, Object> values) {
        String sql = "INSERT INTO public." + q(table);
        if (values.isEmpty()) sql += " DEFAULT VALUES";
        else sql += " (" + String.join(",", values.keySet().stream().map(ErpWriteController::q).toList())
                + ") VALUES (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")";
        return jdbc.queryForObject(sql + " RETURNING id", Object.class, values.values().toArray());
    }

    @GetMapping("/bom/{parentId}")
    public List<Map<String, Object>> bom(@PathVariable long parentId) {
        return jdbc.queryForList("SELECT b.component_item_id::text AS component_id,b.quantity::text AS quantity,i.item_no,i.description,i.base_unit_of_measure FROM item_bom b JOIN items i ON i.id=b.component_item_id WHERE b.parent_item_id=? ORDER BY b.component_item_id", parentId);
    }

    @PutMapping("/bom/{parentId}")
    @Transactional
    public Map<String, Object> saveBom(@PathVariable long parentId, @RequestBody BomDocument document) {
        Map<Long, BigDecimal> desired = bomValues(document.lines());
        Map<Long, BigDecimal> expected = bomValues(document.expected());
        if (desired.isEmpty()) throw bad("BOM cần ít nhất một nguyên vật liệu.");
        if (desired.containsKey(parentId)) throw bad("Thành phẩm không được làm nguyên vật liệu của chính nó.");
        jdbc.execute("SELECT pg_advisory_xact_lock(7821348)");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM items WHERE id=? AND item_type='MANUFACTURED' AND NOT coalesce(blocked,false))", Boolean.class, parentId)))
            throw bad("Chọn Item thành phẩm có production và không bị khóa.");
        Map<Long, BigDecimal> current = new TreeMap<>();
        jdbc.queryForList("SELECT component_item_id,quantity FROM item_bom WHERE parent_item_id=? FOR UPDATE", parentId)
            .forEach(row -> current.put(((Number) row.get("component_item_id")).longValue(), ((BigDecimal) row.get("quantity")).stripTrailingZeros()));
        if (!current.equals(expected)) throw conflict("BOM đã thay đổi. Mở lại biểu mẫu để tải dữ liệu mới trước khi lưu.");
        var removed = new HashSet<>(current.keySet()); removed.removeAll(desired.keySet());
        if (!removed.isEmpty()) {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getAuthorities().stream().noneMatch(a -> Set.of("ROLE_ADMIN", "ROLE_DIRECTOR").contains(a.getAuthority())))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không có quyền xóa dòng BOM.");
            for (Long component : removed) jdbc.update("DELETE FROM item_bom WHERE parent_item_id=? AND component_item_id=?", parentId, component);
        }
        for (var line : desired.entrySet()) {
            jdbc.update("INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(?,?,?) ON CONFLICT(parent_item_id,component_item_id) DO UPDATE SET quantity=EXCLUDED.quantity", parentId, line.getKey(), line.getValue());
        }
        return Map.of("parentId", Long.toString(parentId), "lineCount", desired.size());
    }

    private static Map<Long, BigDecimal> bomValues(List<BomLine> lines) {
        if (lines == null || lines.size() > 500) throw bad("Danh sách BOM không hợp lệ (tối đa 500 dòng).");
        Map<Long, BigDecimal> result = new TreeMap<>();
        for (BomLine line : lines) {
            if (line == null || line.componentId() <= 0 || line.quantity() == null || line.quantity().signum() <= 0
                    || line.quantity().stripTrailingZeros().scale() > 3 || line.quantity().compareTo(new BigDecimal("999999999999999.999")) > 0)
                throw bad("Định mức phải lớn hơn 0, tối đa 3 chữ số thập phân.");
            if (result.put(line.componentId(), line.quantity().stripTrailingZeros()) != null) throw bad("Nguyên vật liệu bị trùng trong BOM.");
        }
        return result;
    }

    public record Field(String name, String type, boolean required, boolean hasDefault,
                        String defaultValue, Integer maxLength, boolean writable) {}

    @GetMapping("/schema/{table}")
    public List<Field> schema(@PathVariable String table) {
        allowed(table);
        return jdbc.query("""
            SELECT column_name, data_type, is_nullable, column_default, character_maximum_length,
                   is_identity, is_generated
            FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """, (rs, index) -> {
                String name = rs.getString("column_name"), value = rs.getString("column_default");
                boolean writable = !Set.of("id", "created_at", "updated_at").contains(name)
                    && !readOnlyField(table, name)
                    && "NO".equals(rs.getString("is_identity")) && "NEVER".equals(rs.getString("is_generated"));
                return new Field(name, rs.getString("data_type"), "NO".equals(rs.getString("is_nullable"))
                    || (table.equals("inventory") && name.equals("item_id")),
                    value != null, value, (Integer) rs.getObject("character_maximum_length"), writable);
            }, table).stream().filter(f -> ErpTables.COLUMNS.get(table).contains(f.name())
                || (table.equals("users") && f.name().equals("password"))).toList();
    }

    @PostMapping("/tables/{table}")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@PathVariable String table, @RequestBody Map<String, Object> input) {
        input = new LinkedHashMap<>(input);
        Map<String,Object> details = SalesHeaderDetails.extract(input);
        writableTable(table);
        autoDocumentNumber(table, input);
        Map<String, Object> itemInput = new LinkedHashMap<>(input);
        BigDecimal openingQuantity = null;
        Long openingLocationId = null;
        boolean openingStockProvided = false;
        if (table.equals("items")) {
            Object rawQuantity = itemInput.remove("initial_quantity");
            Object rawLocationId = itemInput.remove("initial_location_id");
            openingStockProvided = rawQuantity != null || rawLocationId != null;
            if (openingStockProvided) {
                if (rawQuantity == null || rawLocationId == null)
                    throw bad("Chọn kho và nhập tồn ban đầu cho Item Inventory.");
                Object category = itemInput.get("item_category");
                if (!"INVENTORY".equals(category))
                    throw bad("Chỉ Item loại Inventory mới được nhập tồn ban đầu.");
                try {
                    openingQuantity = new BigDecimal(rawQuantity.toString().trim());
                    openingLocationId = Long.valueOf(rawLocationId.toString().trim());
                } catch (NumberFormatException error) {
                    throw bad("Kho hoặc tồn ban đầu không hợp lệ.");
                }
                if (openingLocationId <= 0 || openingQuantity.signum() < 0
                        || openingQuantity.stripTrailingZeros().scale() > 3
                        || openingQuantity.compareTo(new BigDecimal("999999999999999.999")) > 0)
                    throw bad("Tồn ban đầu phải từ 0 trở lên và tối đa 3 chữ số thập phân.");
                if (!Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM locations WHERE id=?)", Boolean.class, openingLocationId)))
                    throw bad("Kho đã chọn không tồn tại.");
            }
        }
        Map<String, Object> values = validate(table, itemInput, true);
        checkOrder(table, values, null);
        String sql = "INSERT INTO public." + q(table);
        if (values.isEmpty()) sql += " DEFAULT VALUES";
        else sql += " (" + String.join(",", values.keySet().stream().map(ErpWriteController::q).toList())
            + ") VALUES (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")";
        Object id = jdbc.queryForObject(sql + " RETURNING id", Object.class, values.values().toArray());
        if (table.equals("sales_quote_lines")) updateQuoteTotals(values.get("sales_header_id"));
        if (openingStockProvided) {
            jdbc.update("""
                INSERT INTO inventory(item_id,location_id,quantity,average_cost)
                SELECT ?, ?, ?, coalesce(unit_cost,0) FROM items WHERE id=?
                ON CONFLICT (item_id,location_id) DO UPDATE SET quantity=EXCLUDED.quantity
                """, id, openingLocationId, openingQuantity, id);
        }
        new SalesHeaderDetails(jdbc).save(table,id,details);
        return Map.of("id", String.valueOf(id));
    }

    @PutMapping("/tables/{table}/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable String table, @PathVariable long id,
                                      @RequestBody Map<String, Object> input) {
        if (table.equals("purchase_headers")) return updatePurchaseHeader(id, input);
        input = new LinkedHashMap<>(input);
        Map<String,Object> details = SalesHeaderDetails.extract(input);
        writableTable(table);
        Map<String, Object> values = validate(table, input, false);
        if (table.equals("users")) protectAdmins(id, values, false);
        Map<String, Object> original = lock(table, id);
        checkOrder(table, values, original);
        if (values.isEmpty() && details.isEmpty()) throw bad("Không có trường nào để cập nhật.");
        if (values.isEmpty()) {
            // Use a no-op source update so its database status guards still run.
            jdbc.update("UPDATE public." + q(table) + " SET id=id WHERE id=?",id);
            new SalesHeaderDetails(jdbc).save(table,id,details);
            return Map.of("id",Long.toString(id));
        }
        List<Object> parameters = new ArrayList<>(values.values()); parameters.add(id);
        String assignments = String.join(",", values.keySet().stream().map(key -> q(key) + " = ?").toList());
        if (ErpTables.COLUMNS.get(table).contains("updated_at")) assignments += ", updated_at = CURRENT_TIMESTAMP";
        jdbc.update("UPDATE public." + q(table) + " SET " + assignments + " WHERE id = ?", parameters.toArray());
        if (table.equals("sales_quote_lines")) {
            updateQuoteTotals(original.get("sales_header_id"));
            if (values.containsKey("sales_header_id")
                    && !Objects.equals(original.get("sales_header_id"), values.get("sales_header_id")))
                updateQuoteTotals(values.get("sales_header_id"));
        }
        new SalesHeaderDetails(jdbc).save(table,id,details);
        return Map.of("id", Long.toString(id));
    }

    @Transactional
    public void delete(String table, long id) { delete(table, id, false); }

    @DeleteMapping("/tables/{table}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable String table, @PathVariable long id,
                       @RequestParam(defaultValue = "false") boolean releaseReservations) {
        if (table.equals("inventory")) { deleteInventoryAsAdmin(id, releaseReservations); return; }
        if (table.equals("purchase_headers")) { deletePurchaseHeader(id); return; }
        writableTable(table);
        allowed(table);
        if (table.equals("users")) protectAdmins(id, Map.of(), true);
        Map<String, Object> original = lock(table, id);
        checkOrder(table, Map.of(), original);
        Object quoteHeaderId = table.equals("sales_quote_lines") ? original.get("sales_header_id") : null;
        // Block implicit cascades: deleting one row must not silently delete its children.
        List<Map<String, Object>> references = jdbc.queryForList("""
            SELECT s.relname AS child_table, a.attname AS child_column
            FROM pg_constraint c JOIN pg_class s ON s.oid=c.conrelid
            JOIN pg_namespace ns ON ns.oid=s.relnamespace
            JOIN pg_class p ON p.oid=c.confrelid JOIN pg_namespace np ON np.oid=p.relnamespace
            JOIN pg_attribute a ON a.attrelid=s.oid AND a.attnum=c.conkey[1]
            WHERE c.contype='f' AND ns.nspname='public' AND np.nspname='public' AND p.relname=?
            """, table);
        for (Map<String, Object> reference : references) {
            String child = reference.get("child_table").toString(), column = reference.get("child_column").toString();
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM public." + q(child)
                + " WHERE " + q(column) + " = ?)", Boolean.class, id);
            if (Boolean.TRUE.equals(exists)) throw conflict("Bản ghi đang được sử dụng trong " + child + ". Xử lý dữ liệu liên quan trước khi xóa.");
        }
        jdbc.update("DELETE FROM public." + q(table) + " WHERE id = ?", id);
        if (quoteHeaderId != null) updateQuoteTotals(quoteHeaderId);
    }

    Map<String, Object> validate(String table, Map<String, Object> input, boolean creating) {
        List<Field> fields = schema(table);
        Map<String, Field> writable = new LinkedHashMap<>();
        fields.stream().filter(Field::writable).forEach(f -> writable.put(f.name(), f));
        for (String key : input.keySet()) if (!writable.containsKey(key)) throw bad("Không được ghi trường " + key);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : writable.values()) {
            if (!input.containsKey(field.name())) {
                if (creating && field.required() && !field.hasDefault()) throw bad("Thiếu trường " + field.name());
                continue;
            }
            Object raw = input.get(field.name());
            String value = raw == null ? null : raw.toString().trim();
            if (value == null || value.isEmpty()) {
                if (field.name().equals("password") && !creating) continue;
                if (field.required()) throw bad("Vui lòng nhập " + field.name());
                values.put(field.name(), null); continue;
            }
            if (field.maxLength() != null && value.length() > field.maxLength()) throw bad(field.name() + " vượt độ dài cho phép.");
            if (field.name().equals("email") && !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw bad("Email không hợp lệ.");
            if (field.name().equals("username") && !value.matches("[A-Za-z0-9_.-]{3,64}")) throw bad("Tên đăng nhập cần 3-64 ký tự: chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
            if (field.name().equals("role")) {
                try { org.example.user_module.config.AccountRole.valueOf(value); }
                catch (IllegalArgumentException error) { throw bad("Quyền phải là ADMIN, DIRECTOR, HR, MANAGER, LEADER hoặc EMPLOYEE."); }
            }
            if (table.equals("items") && field.name().equals("item_category")
                    && !Set.of("INVENTORY", "SERVICE", "NON_INVENTORY").contains(value))
                throw bad("Type phải là Inventory, Service hoặc Non-Inventory.");
            if (table.equals("items") && field.name().equals("item_type")
                    && !Set.of("PURCHASED", "MANUFACTURED").contains(value))
                throw bad("Origin phải là Purchased hoặc Manufactured.");
            if (table.equals("items") && field.name().equals("base_unit_of_measure")
                    && !Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM units_of_measure WHERE code=? AND active=true)",
                            Boolean.class, value)))
                throw bad("Đơn vị tính cơ bản không tồn tại hoặc đã ngừng sử dụng trong Unit.");
            try {
                Object converted = switch (field.type()) {
                    case "bigint" -> Long.valueOf(value);
                    case "integer" -> Integer.valueOf(value);
                    case "smallint" -> Short.valueOf(value);
                    case "numeric", "decimal" -> new BigDecimal(value);
                    case "boolean" -> {
                        if (!value.equals("true") && !value.equals("false")) throw bad("Giá trị boolean không hợp lệ.");
                        yield Boolean.valueOf(value);
                    }
                    case "date" -> Date.valueOf(value);
                    case "timestamp without time zone" -> Timestamp.valueOf((value.length() == 16 ? value + ":00" : value).replace('T', ' '));
                    default -> value;
                };
                if (field.name().equals("password")) {
                    if (value.length() < 8 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw bad("Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte.");
                    converted = new BCryptPasswordEncoder().encode(value);
                }
                if (field.name().equals("quantity") && converted instanceof BigDecimal quantity
                        && quantity.stripTrailingZeros().scale() > 3)
                    throw bad("Số lượng chỉ được có tối đa 3 chữ số thập phân.");
                values.put(field.name(), converted);
            } catch (IllegalArgumentException error) { throw bad("Giá trị không hợp lệ: " + field.name()); }
        }
        if (creating && table.equals("users") && (values.get("password") == null || values.get("email") == null || values.get("name") == null))
            throw bad("Tên, email và mật khẩu là bắt buộc khi tạo người dùng.");
        return values;
    }

    private void deleteInventoryAsAdmin(long id, boolean releaseReservations) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Admin được xóa bản ghi tồn kho.");
        // Same lock order as stock triggers; adjustment and delete roll back together.
        jdbc.execute("SELECT pg_advisory_xact_lock(7821348)");
        Map<String, Object> original = lock("inventory", id);
        BigDecimal reserved = new BigDecimal(Objects.toString(original.get("reserved_quantity"), "0"));
        if (reserved.signum() != 0 && !releaseReservations) throw conflict("Tồn kho đang có số lượng giữ chỗ; hãy giải phóng giữ chỗ trước khi xóa.");
        jdbc.execute("SELECT set_config('erp.admin_inventory_delete','on',true)");
        if (reserved.signum() != 0) {
            jdbc.update("""
                INSERT INTO inventory_ledger(entry_no,product_id,item_id,location_id,document_type,document_id,
                    quantity_in,quantity_out,unit_cost,balance_quantity,description)
                VALUES ('ADMIN-'||gen_random_uuid(),?,?,?,'ADJUSTMENT',?,0,0,?,?,?)
                """, original.get("product_id"), original.get("item_id"), original.get("location_id"), id,
                original.get("average_cost"), original.get("quantity"),
                "Admin " + auth.getName() + " giải phóng giữ chỗ " + reserved.toPlainString() + " trước khi xóa Inventory #" + id);
            jdbc.update("UPDATE inventory SET reserved_quantity=0 WHERE id=?", id);
        }
        jdbc.execute("SELECT set_config('erp.stock_source','ADJUSTMENT',true)");
        jdbc.execute("SELECT set_config('erp.stock_document','',true)");
        // Existing stock triggers record the reduction and refresh item/product totals.
        jdbc.update("UPDATE inventory SET quantity=0 WHERE id=?", id);

        jdbc.update("DELETE FROM inventory WHERE id=?", id);
        jdbc.execute("SELECT set_config('erp.admin_inventory_delete','off',true)");
    }

    private static final Set<String> PURCHASE_EDIT_FIELDS = Set.of(
        "order_date", "posting_date", "requested_receipt_date", "promised_receipt_date",
        "vendor_reference", "external_document_no", "payment_terms_code", "shipment_method_code",
        "salesperson_code", "location_code", "notes");

    private void guardPurchaseChange(long id) {
        Map<String, Object> header = lock("purchase_headers", id);
        if (!"DRAFT".equals(header.get("status")))
            throw conflict("Chỉ được sửa hoặc xóa chứng từ mua ở trạng thái Nháp.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM purchase_receipts WHERE purchase_order_id=?)
                OR EXISTS(SELECT 1 FROM payment_allocations WHERE purchase_invoice_id=?)
                OR EXISTS(SELECT 1 FROM purchase_receipt_lines r JOIN purchase_lines l
                    ON l.id=r.purchase_order_line_id WHERE l.purchase_header_id=?)
                OR EXISTS(SELECT 1 FROM purchase_lines r JOIN purchase_lines l
                    ON l.id=r.source_purchase_line_id WHERE l.purchase_header_id=? AND r.purchase_header_id<>?)
            """, Boolean.class, id, id, id, id, id)))
            throw conflict("Chứng từ đã có phiếu nhập, hóa đơn hoặc thanh toán liên quan; không thể sửa/xóa.");
    }

    private Map<String, Object> updatePurchaseHeader(long id, Map<String, Object> input) {
        guardPurchaseChange(id);
        if (!PURCHASE_EDIT_FIELDS.containsAll(input.keySet()))
            throw bad("Không được đổi loại chứng từ, nhà cung cấp, tiền tệ hoặc số tiền bằng thao tác sửa thông tin chung.");
        Map<String, Object> values = validate("purchase_headers", input, false);
        if (values.isEmpty()) throw bad("Không có trường nào để cập nhật.");
        if (values.containsKey("location_code")) validatePurchaseLocation((String) values.get("location_code"));
        List<Object> args = new ArrayList<>(values.values()); args.add(id);
        String assignments = String.join(",", values.keySet().stream().map(key -> q(key) + "=?").toList());
        jdbc.update("UPDATE purchase_headers SET " + assignments + ",updated_at=CURRENT_TIMESTAMP WHERE id=?", args.toArray());
        return Map.of("id", Long.toString(id));
    }

    private void deletePurchaseHeader(long id) {
        guardPurchaseChange(id);
        // Lines belong to this document; FK constraints still protect other references.
        jdbc.update("DELETE FROM purchase_lines WHERE purchase_header_id=?", id);
        jdbc.update("DELETE FROM purchase_headers WHERE id=?", id);
    }

    private void autoDocumentNumber(String table, Map<String, Object> input) {
        if (table.equals("sales_headers")
                && !"QUOTE".equals(Objects.toString(input.get("document_type"), "QUOTE").toUpperCase(Locale.ROOT)))
            return;
        String[] numbering = switch (table) {
            case "items" -> new String[]{"item_no", "ITEM"};
            case "sales_headers" -> new String[]{"quote_no", "SQ"};
            case "sales_invoices" -> new String[]{"invoice_no", "S-INV"};
            case "sales_returns" -> new String[]{"return_no", "SRO"};
            case "sales_shipments" -> new String[]{"shipment_no", "SS"};
            case "purchase_returns" -> new String[]{"return_no", "PR"};
            case "purchase_receipts" -> new String[]{"receipt_no", "GR"};
            case "production_orders" -> new String[]{"production_no", "PRO"};
            default -> null;
        };
        if (numbering != null && Objects.toString(input.get(numbering[0]), "").isBlank())
            input.put(numbering[0], jdbc.queryForObject("SELECT erp_next_document_no(?)", String.class, numbering[1]));
    }

    private static boolean readOnlyField(String table, String field) {
        return (table.equals("sales_headers") && Set.of("sales_order_id", "status", "subtotal", "discount_amount", "vat_amount", "total_amount").contains(field)) || (table.equals("items") && field.equals("quantity"))
            || (table.equals("purchase_headers") && Set.of("document_no", "status", "discount_amount", "vat_amount", "total_amount").contains(field))
            || (table.equals("sales_returns") && field.equals("received_at"))
            || (table.equals("sales_return_lines") && field.equals("item_id"))
            || (table.equals("inventory") && field.equals("product_id"))
            || (table.equals("production_orders") && field.equals("completed_at"));
    }

    private static void writableTable(String table) {
        if (Set.of("purchase_headers", "purchase_lines").contains(table))
            throw conflict("Purchase Documents và Purchase Lines phải được tạo từ biểu mẫu Purchasing để lưu đồng thời phần đầu và dòng hàng.");
        if (Set.of("inventory_ledger", "production_components", "sales_lines").contains(table))
            throw conflict("Bảng chỉ đọc. Dùng dòng đơn bán, phiếu nhập hoặc lệnh sản xuất để cập nhật tồn kho.");
    }

    private void protectAdmins(long id, Map<String, Object> values, boolean deleting) {
        var admins = jdbc.queryForList("SELECT id,username FROM users WHERE role='ADMIN' ORDER BY id FOR UPDATE");
        var target = admins.stream().filter(row -> ((Number) row.get("id")).longValue() == id).findFirst();
        boolean removingAdmin = deleting || (values.containsKey("role") && !"ADMIN".equals(values.get("role")));
        if (target.isPresent() && removingAdmin) {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName().equals(target.get().get("username"))) throw conflict("Không thể xóa hoặc hạ quyền tài khoản admin đang đăng nhập.");
            if (admins.size() <= 1) throw conflict("Phải giữ ít nhất một tài khoản admin.");
        }
    }

    private Map<String, Object> lock(String table, long id) {
        allowed(table);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT "
            + String.join(",", ErpTables.COLUMNS.get(table).stream().map(ErpWriteController::q).toList())
            + " FROM public." + q(table) + " WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bản ghi không còn tồn tại.");
        return rows.getFirst();
    }

    private void checkOrder(String table, Map<String, Object> values, Map<String, Object> original) {
        if (table.equals("payment_allocations")) {
            Object rawInvoiceId = values.getOrDefault("purchase_invoice_id",
                    original == null ? null : original.get("purchase_invoice_id"));
            if (rawInvoiceId != null) {
                Map<String, Object> invoice = lock("purchase_headers", Long.parseLong(rawInvoiceId.toString()));
                if (!"INVOICE".equals(invoice.get("document_type")))
                    throw bad("Chỉ Purchase Invoice mới được gắn với thanh toán nhà cung cấp.");
            }
        }
        if (table.equals("purchase_receipts")) {
            Object rawOrderId = values.getOrDefault("purchase_order_id", original == null ? null : original.get("purchase_order_id"));
            Object rawVendorId = values.getOrDefault("vendor_id", original == null ? null : original.get("vendor_id"));
            if (rawOrderId != null) {
                Map<String, Object> order = lock("purchase_headers", Long.parseLong(rawOrderId.toString()));
                if (!"ORDER".equals(order.get("document_type")))
                    throw bad("Purchase Receipt chỉ được liên kết với Purchase Order.");
                if (rawVendorId != null && !order.get("vendor_id").toString().equals(rawVendorId.toString()))
                    throw bad("Nhà cung cấp trên phiếu nhập phải khớp với Purchase Order.");
            }
        }
        if (table.equals("purchase_receipt_lines")) {
            Object rawReceiptId = values.getOrDefault("receipt_id", original == null ? null : original.get("receipt_id"));
            Object rawOrderLineId = values.getOrDefault("purchase_order_line_id",
                    original == null ? null : original.get("purchase_order_line_id"));
            Object rawItemId = values.getOrDefault("item_id", original == null ? null : original.get("item_id"));
            if (rawOrderLineId != null) {
                if (rawReceiptId == null) throw bad("Chọn phiếu nhập trước khi chọn Purchase Line.");
                List<Map<String, Object>> receipts = jdbc.queryForList(
                        "SELECT purchase_order_id FROM purchase_receipts WHERE id=? FOR UPDATE",
                        Long.parseLong(rawReceiptId.toString()));
                if (receipts.isEmpty() || receipts.getFirst().get("purchase_order_id") == null)
                    throw bad("Phiếu nhập không liên kết với Purchase Order.");
                long orderId = ((Number) receipts.getFirst().get("purchase_order_id")).longValue();
                List<Map<String, Object>> orderLines = jdbc.queryForList("""
                    SELECT id,item_id,quantity FROM purchase_lines
                    WHERE id=? AND purchase_header_id=? AND document_type='ORDER' FOR UPDATE
                    """, Long.parseLong(rawOrderLineId.toString()), orderId);
                if (orderLines.isEmpty()) throw bad("Purchase Line phải thuộc Purchase Order của phiếu nhập.");
                Map<String, Object> orderLine = orderLines.getFirst();
                if (rawItemId != null && !rawItemId.toString().equals(orderLine.get("item_id").toString()))
                    throw bad("Mặt hàng trên dòng phiếu nhập phải khớp với Purchase Line.");
                BigDecimal requested = values.get("quantity") instanceof BigDecimal quantity ? quantity
                        : original != null && original.get("quantity") instanceof BigDecimal quantity ? quantity : BigDecimal.ZERO;
                BigDecimal prior = Objects.requireNonNullElse(jdbc.queryForObject("""
                    SELECT sum(quantity) FROM purchase_receipt_lines
                    WHERE purchase_order_line_id=? AND id<>coalesce(?,0)
                    """, BigDecimal.class, rawOrderLineId,
                        original == null ? null : original.get("id")), BigDecimal.ZERO);
                if (prior.add(requested).compareTo((BigDecimal) orderLine.get("quantity")) > 0)
                    throw bad("Tổng số lượng nhận vượt Purchase Line.");
            }
        }
        if (table.equals("sales_headers")) {
            if (original == null) throw conflict("Tạo chứng từ qua form Sales để lưu đầy đủ các dòng hàng.");
            if (!"QUOTE".equals(original.get("document_type")) || !"DRAFT".equals(original.get("status")) || original.get("sales_order_id") != null)
                throw conflict("Chỉ được sửa/xóa báo giá nháp chưa chuyển đơn. Các chứng từ khác phải sửa tại chứng từ gốc.");
            if (values.keySet().stream().anyMatch(key -> Set.of("document_type", "related_sales_order_id", "sales_return_id", "sales_invoice_id").contains(key)))
                throw conflict("Không được thay đổi loại hoặc liên kết chứng từ bán.");
        }
        if (table.equals("sales_orders") && original != null && !"OPEN".equals(original.get("status")))
            throw conflict("Chỉ được sửa hoặc xóa đơn bán đang OPEN.");
        if (table.equals("sales_quote_lines")) {
            Set<Object> headers = new HashSet<>();
            if (original != null) headers.add(original.get("sales_header_id"));
            if (values.containsKey("sales_header_id")) headers.add(values.get("sales_header_id"));
            for (Object headerId : headers) if (headerId != null) {
                Map<String, Object> quote = lock("sales_headers", Long.parseLong(headerId.toString()));
                if (!"QUOTE".equals(quote.get("document_type")) || !"DRAFT".equals(quote.get("status"))
                        || quote.get("sales_order_id") != null)
                    throw conflict("Chỉ được sửa hoặc xóa Sales Lines của báo giá nháp chưa chuyển đơn.");
            }
        }
        if (table.equals("sales_order_lines") || table.equals("sales_lines")) {
            String key = table.equals("sales_lines") ? "sales_header_id" : "sales_order_id";
            Set<Object> parents = new HashSet<>();
            if (original != null) parents.add(original.get(key));
            if (values.containsKey(key)) parents.add(values.get(key));
            for (Object parent : parents) if (parent != null) {
                Map<String, Object> order = lock("sales_orders", Long.parseLong(parent.toString()));
                if (!"OPEN".equals(order.get("status"))) throw conflict("Đơn bán không còn OPEN; không được thay đổi dòng hàng.");
            }
        }
    }

    private void updateQuoteTotals(Object headerId) {
        jdbc.update("""
            UPDATE sales_headers header SET
              subtotal=totals.subtotal, discount_amount=totals.discount_amount,
              vat_amount=totals.vat_amount, total_amount=totals.subtotal+totals.vat_amount,
              updated_at=CURRENT_TIMESTAMP
            FROM (
              SELECT coalesce(sum(round(quantity*unit_price*(1-discount_percent/100),2)),0) AS subtotal,
                     coalesce(sum(round(quantity*unit_price*discount_percent/100,2)),0) AS discount_amount,
                     coalesce(sum(round(quantity*unit_price*(1-discount_percent/100)*vat_percent/100,2)),0) AS vat_amount
              FROM sales_quote_lines WHERE sales_header_id=?
            ) totals
            WHERE header.id=?
            """, headerId, headerId);
    }

    private static void allowed(String table) {
        if (!ErpTables.COLUMNS.containsKey(table)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bảng không tồn tại.");
    }
    private static String q(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "Không thể thực hiện thao tác.")));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> constraint(DataIntegrityViolationException error) {
        String message = "Dữ liệu không hợp lệ. Kiểm tra trường bắt buộc, số lượng, số tiền và trạng thái.";
        Throwable cause = error.getMostSpecificCause();
        if (cause instanceof java.sql.SQLException sql) {
            if ("23514".equals(sql.getSQLState()) && sql.getMessage().contains("STOCK:")) {
                message = sql.getMessage().substring(sql.getMessage().indexOf("STOCK:") + 6).split("\\n")[0].trim();
            }
            if ("23505".equals(sql.getSQLState())) message = "Mã hoặc thông tin định danh đã tồn tại.";
            if ("23503".equals(sql.getSQLState())) message = "Dữ liệu liên quan không tồn tại hoặc bản ghi đang được sử dụng.";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }
}
