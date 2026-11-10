package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Posting boundaries: drafts, physical receiving, invoices and settlements are distinct events. */
@RestController
@RequestMapping("/api/erp/purchase-workflow")
public class PurchaseWorkflowController {
    private final JdbcTemplate db;
    private final ErpWriteController writer;
    private static final Set<String> ROLES=Set.of("INVENTORY","EXPENSE","VAT","PAYABLE","BANK","RETURN_CLEARING","ADJUSTMENT");
    public PurchaseWorkflowController(JdbcTemplate db, ErpWriteController writer) { this.db=db; this.writer=writer; }
    public record ReceiveLine(long lineId, BigDecimal quantity) {}
    public record ReceiveRequest(UUID requestKey, LocalDate postingDate, List<ReceiveLine> lines) {}
    public record Settlement(UUID requestKey, LocalDate postingDate, BigDecimal amount, BigDecimal vatAmount, String reference, Long returnOrderId) {}
    public record Posting(LocalDate postingDate) {}
    public record ReceiveInvoiceRequest(ReceiveRequest receipt, String vendorInvoiceNo) {}

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static BigDecimal n(Object value) { return value==null?BigDecimal.ZERO:new BigDecimal(value.toString()); }
    private static long id(Object value) { return Long.parseLong(value.toString()); }
    private static String s(Object value) { return Objects.toString(value,""); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }
    private static LocalDate date(LocalDate value) { return value==null?LocalDate.now():value; }
    private void serialize() { db.execute("SELECT pg_advisory_xact_lock(7821348)"); }
    private Map<String,Object> header(long key) {
        var rows=db.queryForList("SELECT * FROM purchase_headers WHERE id=? FOR UPDATE",key);
        if(rows.isEmpty()) throw bad("Không tìm thấy chứng từ mua.");
        return rows.getFirst();
    }
    private List<Map<String,Object>> lines(long key) {
        return db.queryForList("SELECT l.*,i.item_category,i.item_no FROM purchase_lines l JOIN items i ON i.id=l.item_id WHERE l.purchase_header_id=? ORDER BY l.line_no FOR UPDATE OF l",key);
    }
    private void vendor(Map<String,Object> h) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM vendors WHERE id=? AND NOT coalesce(blocked,false))",Boolean.class,h.get("vendor_id")))) throw bad("Nhà cung cấp không tồn tại hoặc đã bị khóa.");
    }
    private void state(Map<String,Object> h,String type,String status) {
        if(!type.equals(h.get("document_type")) || !status.equals(h.get("status"))) throw bad("Thao tác yêu cầu "+type+" ở trạng thái "+status+".");
    }
    private Map<String,Object> result(long key) { return Map.of("id",Long.toString(key)); }

    @GetMapping("/setup")
    public Map<String,Object> setup() {
        return Map.of("roles",ROLES,"mappings",db.queryForList("SELECT role,account_id FROM purchase_posting_setup"),
            "accounts",db.queryForList("SELECT id,account_no,account_name FROM accounts WHERE active=true ORDER BY account_no"));
    }
    @PutMapping("/setup") @Transactional
    public void setup(@RequestBody Map<String,Long> mappings) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || auth.getAuthorities().stream().noneMatch(a->Set.of("ROLE_ADMIN","ROLE_DIRECTOR").contains(a.getAuthority()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!mappings.keySet().equals(ROLES)) throw bad("Cần cấu hình đủ 7 nhóm tài khoản Purchase.");
        serialize();
        for(var role:ROLES) {
            if(mappings.get(role)==null || !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND active=true)",Boolean.class,mappings.get(role)))) throw bad("Tài khoản không hợp lệ: "+role);
            db.update("INSERT INTO purchase_posting_setup VALUES(?,?) ON CONFLICT(role) DO UPDATE SET account_id=excluded.account_id",role,mappings.get(role));
        }
    }
    @GetMapping("/{key}/history")
    public Map<String,Object> history(@PathVariable long key) {
        return Map.of("entries",db.queryForList("SELECT * FROM purchase_vendor_entries WHERE invoice_id=? ORDER BY id",key),
            "balance",balance(key),"receipts",db.queryForList("SELECT * FROM purchase_receipts WHERE purchase_order_id=? ORDER BY id",key),
            "gl",db.queryForList("SELECT g.*,a.account_no,a.account_name,k.currency_code FROM purchase_gl_links k JOIN general_ledger g ON g.id=k.ledger_id JOIN accounts a ON a.id=g.account_id WHERE k.purchase_header_id=? ORDER BY g.id",key),
            "values",db.queryForList("SELECT v.* FROM purchase_value_entries v JOIN purchase_lines l ON l.id=v.purchase_line_id WHERE l.purchase_header_id=? ORDER BY v.id",key));
    }
    private BigDecimal balance(long invoice) { return n(db.queryForObject("SELECT coalesce(sum(amount),0) FROM purchase_vendor_entries WHERE invoice_id=?",BigDecimal.class,invoice)); }

    @PostMapping("/{key}/make-order") @Transactional
    public Map<String,Object> makeOrder(@PathVariable long key) {
        serialize(); var h=header(key);
        var existing=db.queryForList("SELECT id FROM purchase_headers WHERE source_quote_id=?",key);
        if(!existing.isEmpty()) return result(id(existing.getFirst().get("id")));
        state(h,"QUOTE","DRAFT"); vendor(h);
        var copied=new ArrayList<ErpWriteController.PurchaseDocumentLineInput>();
        for(var l:lines(key)) copied.add(new ErpWriteController.PurchaseDocumentLineInput(null,id(l.get("item_id")),s(l.get("description")),s(l.get("unit_of_measure")),n(l.get("quantity")),n(l.get("unit_price")),n(l.get("discount_percent")),n(l.get("vat_percent")),s(l.get("location_code"))));
        var input=new LinkedHashMap<>(h); input.put("document_type","ORDER"); input.put("posting_date",LocalDate.now().toString());
        var created=writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(input,copied));
        long order=id(created.get("id"));
        db.update("UPDATE purchase_headers SET source_quote_id=? WHERE id=?",key,order);
        db.update("UPDATE purchase_headers SET status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE id=?",key);
        return result(order);
    }
    @PostMapping("/{key}/release") @Transactional
    public Map<String,Object> release(@PathVariable long key) {
        serialize(); var h=header(key); vendor(h);
        if(!Set.of("ORDER","RETURN_ORDER").contains(s(h.get("document_type")))) throw bad("Chỉ Release Order hoặc Return Order.");
        if("RELEASED".equals(h.get("status"))) return result(key);
        if(!"DRAFT".equals(h.get("status"))) throw bad("Chỉ Release chứng từ nháp.");
        if(lines(key).isEmpty()) throw bad("Chứng từ cần ít nhất một dòng.");
        db.update("UPDATE purchase_headers SET status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE id=?",key);
        return result(key);
    }
    static void validateReceive(ReceiveRequest r) {
        if(r==null || r.requestKey()==null || r.lines()==null || r.lines().isEmpty() || r.lines().size()>500) throw bad("Chọn từ 1 đến 500 dòng nhận hàng và mã yêu cầu.");
        Set<Long> unique=new HashSet<>();
        for(var l:r.lines()) if(l==null || l.lineId()<=0 || !unique.add(l.lineId()) || l.quantity()==null || l.quantity().signum()<=0 || l.quantity().stripTrailingZeros().scale()>3) throw bad("Dòng nhận trùng hoặc số lượng không hợp lệ (tối đa 3 số lẻ).");
    }
    @PostMapping("/{key}/receive") @Transactional
    public Map<String,Object> receive(@PathVariable long key,@RequestBody ReceiveRequest r) {
        validateReceive(r); serialize(); var h=header(key); state(h,"ORDER","RELEASED"); vendor(h);
        String signature=s(r.postingDate())+"|"+r.lines().stream().sorted(Comparator.comparingLong(ReceiveLine::lineId)).map(l->l.lineId()+":"+l.quantity().stripTrailingZeros().toPlainString()).toList();
        var retry=db.queryForList("SELECT order_id,receipt_id,payload->>'signature' AS signature FROM purchase_receiving_requests WHERE request_key=?",r.requestKey());
        if(!retry.isEmpty()) {
            if(id(retry.getFirst().get("order_id"))!=key || !signature.equals(retry.getFirst().get("signature"))) throw bad("Mã yêu cầu đã dùng cho nội dung khác.");
            return Map.of("id",s(retry.getFirst().get("receipt_id")),"receipt",true);
        }
        var all=new HashMap<Long,Map<String,Object>>(); for(var l:lines(key)) all.put(id(l.get("id")),l);
        for(var selection:r.lines()) {
            var l=all.get(selection.lineId()); if(l==null) throw bad("Dòng không thuộc đơn mua này.");
            if(selection.quantity().compareTo(n(l.get("quantity")).subtract(n(l.get("quantity_received"))))>0) throw bad("Số lượng nhận vượt số lượng còn lại của "+l.get("item_no"));
            if(!"INVENTORY".equals(l.get("item_category"))) throw bad("Luồng nhận kho hiện yêu cầu Item loại Inventory.");
            location(l);
        }
        String no=db.queryForObject("SELECT erp_next_document_no('GR')",String.class);
        long receipt=db.queryForObject("INSERT INTO purchase_receipts(receipt_no,purchase_order_id,vendor_id,receipt_date,location_code) VALUES(?,?,?,?,?) RETURNING id",Long.class,no,key,h.get("vendor_id"),java.sql.Date.valueOf(date(r.postingDate())),h.get("location_code"));
        int seq=10000;
        for(var selection:r.lines()) {
            var l=all.get(selection.lineId());
            long loc=location(l);
            var stock=db.queryForList("SELECT quantity,average_cost FROM inventory WHERE item_id=? AND location_id=?",l.get("item_id"),loc);
            BigDecimal before=stock.isEmpty()?BigDecimal.ZERO:n(stock.getFirst().get("quantity"));
            BigDecimal priorCost=stock.isEmpty()?BigDecimal.ZERO:n(stock.getFirst().get("average_cost")).multiply(before);
            BigDecimal expected=netCost(h,l,selection.quantity());
            long receiptLine=db.queryForObject("INSERT INTO purchase_receipt_lines(receipt_id,line_no,purchase_order_line_id,item_id,quantity,unit_of_measure,location_code) VALUES(?,?,?,?,?,?,?) RETURNING id",Long.class,receipt,seq,selection.lineId(),l.get("item_id"),selection.quantity(),l.get("unit_of_measure"),l.get("location_code"));
            if("VND".equals(h.get("currency_code"))) db.update("UPDATE inventory SET average_cost=? WHERE item_id=? AND location_id=?",priorCost.add(expected).divide(before.add(selection.quantity()),2,RoundingMode.HALF_UP),l.get("item_id"),loc);
            value(l,receiptLine,"EXPECTED",selection.quantity(),expected,date(r.postingDate()),s(h.get("currency_code")));
            seq+=10000;
        }
        db.update("INSERT INTO purchase_receiving_requests(request_key,order_id,receipt_id,payload) VALUES(?,?,?,jsonb_build_object('signature',?::text))",r.requestKey(),key,receipt,signature);
        return Map.of("id",Long.toString(receipt),"receipt",true);
    }
    private long location(Map<String,Object> l) {
        var rows=db.queryForList("SELECT id FROM locations WHERE code=?",l.get("location_code"));
        if(rows.isEmpty()) throw bad("Chọn Location hợp lệ trên tất cả dòng hàng.");
        return id(rows.getFirst().get("id"));
    }
    @PostMapping("/{key}/receive-invoice") @Transactional
    public Map<String,Object> receiveInvoice(@PathVariable long key,@RequestBody ReceiveInvoiceRequest r) {
        if(r==null || r.vendorInvoiceNo()==null || r.vendorInvoiceNo().isBlank() || r.vendorInvoiceNo().length()>100) throw bad("Nhập Vendor Invoice No. trước khi nhận hàng và hóa đơn.");
        validateReceive(r.receipt());serialize();var order=header(key);
        long receipt=id(receive(key,r.receipt()).get("id"));
        var existing=db.queryForList("SELECT invoice_id FROM purchase_receiving_requests WHERE request_key=?",r.receipt().requestKey());
        if(!existing.isEmpty() && existing.getFirst().get("invoice_id")!=null) {
            long invoice=id(existing.getFirst().get("invoice_id"));
            if(!r.vendorInvoiceNo().equals(header(invoice).get("external_document_no"))) throw bad("Mã yêu cầu đã gắn với hóa đơn khác.");
            return result(invoice);
        }
        var inputs=new ArrayList<ErpWriteController.PurchaseDocumentLineInput>();
        for(var l:db.queryForList("SELECT r.*,p.unit_price,p.discount_percent,p.vat_percent FROM purchase_receipt_lines r JOIN purchase_lines p ON p.id=r.purchase_order_line_id WHERE r.receipt_id=? ORDER BY r.line_no",receipt))
            inputs.add(new ErpWriteController.PurchaseDocumentLineInput(id(l.get("id")),null,null,null,n(l.get("quantity")),n(l.get("unit_price")),n(l.get("discount_percent")),n(l.get("vat_percent")),s(l.get("location_code"))));
        var h=new LinkedHashMap<>(order);h.put("document_type","INVOICE");h.put("external_document_no",r.vendorInvoiceNo());
        long invoice=id(writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(h,inputs)).get("id"));
        invoice(invoice,new Posting(r.receipt().postingDate()));
        db.update("UPDATE purchase_receiving_requests SET invoice_id=? WHERE request_key=?",invoice,r.receipt().requestKey());
        return result(invoice);
    }
    private static BigDecimal netCost(Map<String,Object> h,Map<String,Object> l,BigDecimal quantity) {
        return money(quantity.multiply(n(l.get("unit_price"))).multiply(BigDecimal.ONE.subtract(n(l.get("discount_percent")).movePointLeft(2))).multiply(BigDecimal.ONE.subtract(n(h.get("discount_percent")).movePointLeft(2))));
    }
    private void value(Map<String,Object> l,Long receipt,String type,BigDecimal quantity,BigDecimal amount,LocalDate day,String currency) {
        db.update("INSERT INTO purchase_value_entries(purchase_line_id,receipt_line_id,entry_type,quantity,amount,currency_code,posting_date) VALUES(?,?,?,?,?,?,?)",l.get("id"),receipt,type,quantity,amount,currency,java.sql.Date.valueOf(day));
    }
    @PostMapping("/{key}/post-invoice") @Transactional
    public Map<String,Object> invoice(@PathVariable long key,@RequestBody Posting request) {
        serialize(); var h=header(key);
        if("INVOICE".equals(h.get("document_type")) && "POSTED".equals(h.get("status"))) return result(key);
        state(h,"INVOICE","DRAFT"); vendor(h);
        if(s(h.get("external_document_no")).isBlank()) throw bad("Nhập Vendor Invoice No. (Số chứng từ ngoài) trước khi Post Invoice.");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM purchase_headers WHERE document_type='INVOICE' AND status='POSTED' AND vendor_id=? AND external_document_no=? AND id<>?)",Boolean.class,h.get("vendor_id"),h.get("external_document_no"),key))) throw bad("Số hóa đơn nhà cung cấp đã được đăng sổ.");
        var ls=lines(key); if(ls.isEmpty()) throw bad("Hóa đơn không có dòng hàng.");
        for(var l:ls) if(l.get("purchase_receipt_line_id")==null) throw bad("Hóa đơn cũ thiếu dòng phiếu nhập: cần đối chiếu trước khi đăng sổ.");
        LocalDate day=date(request==null?null:request.postingDate());
        BigDecimal total=n(h.get("total_amount")),tax=n(h.get("vat_amount")),net=total.subtract(tax);
        if(total.signum()<0 || net.signum()<0) throw bad("Tổng hóa đơn không hợp lệ.");
        var amounts=new LinkedHashMap<String,BigDecimal>(); amounts.put("INVENTORY",net); amounts.put("VAT",tax); amounts.put("PAYABLE",total.negate());
        gl(key,h,s(h.get("document_no")),"PURCHASE_INVOICE",day,amounts);
        db.update("INSERT INTO purchase_vendor_entries(invoice_id,vendor_id,entry_type,document_no,posting_date,currency_code,amount,reference) VALUES(?,?,'INVOICE',?,?,?,?,?)",key,h.get("vendor_id"),h.get("document_no"),java.sql.Date.valueOf(day),h.get("currency_code"),total,h.get("external_document_no"));
        BigDecimal allocated=BigDecimal.ZERO;
        BigDecimal weight=ls.stream().map(l->n(l.get("line_amount")).subtract(n(l.get("vat_amount")))).reduce(BigDecimal.ZERO,BigDecimal::add);
        for(int i=0;i<ls.size();i++) {
            var l=ls.get(i); BigDecimal amount=i==ls.size()-1?net.subtract(allocated):weight.signum()==0?BigDecimal.ZERO:net.multiply(n(l.get("line_amount")).subtract(n(l.get("vat_amount")))).divide(weight,2,RoundingMode.HALF_UP);
            allocated=allocated.add(amount);
            value(l,id(l.get("purchase_receipt_line_id")),"ACTUAL",n(l.get("quantity")),amount,day,s(h.get("currency_code")));
        }
        db.update("UPDATE purchase_headers SET status='POSTED',posting_date=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",java.sql.Date.valueOf(day),key);
        db.update("UPDATE purchase_lines SET quantity=quantity WHERE purchase_header_id=?",key);
        return result(key);
    }
    @PostMapping("/{key}/ship-return") @Transactional
    public Map<String,Object> shipReturn(@PathVariable long key,@RequestBody Posting request) {
        serialize(); var h=header(key);
        if("RETURN_ORDER".equals(h.get("document_type")) && "POSTED".equals(h.get("status"))) return result(key);
        state(h,"RETURN_ORDER","RELEASED"); vendor(h);
        if(!"VND".equals(h.get("currency_code"))) throw bad("Trả hàng cần tiền tệ VND để khớp giá vốn tồn kho; chưa cấu hình tỷ giá cho ngoại tệ.");
        var ls=lines(key); if(ls.isEmpty()) throw bad("Chưa có dòng trả hàng.");
        LocalDate day=date(request==null?null:request.postingDate()); BigDecimal cost=BigDecimal.ZERO;
        for(var l:ls) {
            if(!"INVENTORY".equals(l.get("item_category"))) throw bad("Chỉ gửi trả kho Item loại Inventory.");
            long loc=location(l);
            BigDecimal avg=n(db.queryForObject("SELECT coalesce((SELECT average_cost FROM inventory WHERE item_id=? AND location_id=?),0)",BigDecimal.class,l.get("item_id"),loc));
            BigDecimal amount=money(avg.multiply(n(l.get("quantity")))); cost=cost.add(amount);
            db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,l.get("item_id"),loc,n(l.get("quantity")).negate(),"PURCHASE_RETURN",key);
            value(l,null,"RETURN",n(l.get("quantity")).negate(),amount.negate(),day,"VND");
        }
        gl(key,h,s(h.get("document_no")),"PURCHASE_RETURN",day,Map.of("RETURN_CLEARING",cost,"INVENTORY",cost.negate()));
        db.update("INSERT INTO purchase_return_shipments(return_order_id,posting_date,inventory_value) VALUES(?,?,?)",key,java.sql.Date.valueOf(day),cost);
        db.update("UPDATE purchase_headers SET status='POSTED',posting_date=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",java.sql.Date.valueOf(day),key);
        return result(key);
    }
    static void validateSettlement(Settlement r) {
        if(r==null || r.requestKey()==null || r.amount()==null || r.amount().signum()<=0 || r.amount().stripTrailingZeros().scale()>2 || r.reference()==null || r.reference().isBlank() || r.reference().length()>200) throw bad("Nhập số tiền dương (tối đa 2 số lẻ), tham chiếu và mã yêu cầu.");
        if(r.vatAmount()!=null && (r.vatAmount().signum()<0 || r.vatAmount().compareTo(r.amount())>0 || r.vatAmount().stripTrailingZeros().scale()>2)) throw bad("VAT điều chỉnh không hợp lệ.");
    }
    @PostMapping("/{key}/pay") @Transactional
    public Map<String,Object> pay(@PathVariable long key,@RequestBody Settlement r) { return settle(key,r,false); }
    @PostMapping("/{key}/credit") @Transactional
    public Map<String,Object> credit(@PathVariable long key,@RequestBody Settlement r) { return settle(key,r,true); }
    private Map<String,Object> settle(long key,Settlement r,boolean credit) {
        validateSettlement(r); serialize(); var h=header(key); state(h,"INVOICE","POSTED");
        String type=credit?"CREDIT_MEMO":"PAYMENT";
        var retry=db.queryForList("SELECT * FROM purchase_vendor_entries WHERE request_key=?",r.requestKey());
        if(!retry.isEmpty()) {
            var old=retry.getFirst(); if(id(old.get("invoice_id"))!=key || !type.equals(old.get("entry_type")) || n(old.get("amount")).negate().compareTo(r.amount())!=0 || n(old.get("vat_amount")).compareTo(n(r.vatAmount()))!=0 || !Objects.equals(old.get("return_order_id"),r.returnOrderId()) || !s(old.get("reference")).equals(r.reference())) throw bad("Mã yêu cầu đã được dùng cho nội dung khác.");
            return result(key);
        }
        BigDecimal available=credit?n(h.get("total_amount")).add(n(db.queryForObject("SELECT coalesce(sum(amount),0) FROM purchase_vendor_entries WHERE invoice_id=? AND entry_type='CREDIT_MEMO'",BigDecimal.class,key))):balance(key);
        if(r.amount().compareTo(available)>0) throw bad(credit?"Số tiền giảm vượt giá trị hóa đơn chưa điều chỉnh.":"Số tiền vượt công nợ còn lại.");
        LocalDate day=date(r.postingDate()); String no=(credit?"P-CM-":"P-PAY-")+r.requestKey();
        var amounts=new LinkedHashMap<String,BigDecimal>(); amounts.put("PAYABLE",r.amount());
        BigDecimal returnCost=BigDecimal.ZERO;
        if(!credit) {
            if(r.returnOrderId()!=null || n(r.vatAmount()).signum()!=0) throw bad("Thanh toán không được có VAT hoặc Return Order.");
            amounts.put("BANK",r.amount().negate());
        } else {
            BigDecimal vat=n(r.vatAmount());
            BigDecimal priorVat=n(db.queryForObject("SELECT coalesce(sum(vat_amount),0) FROM purchase_vendor_entries WHERE invoice_id=? AND entry_type='CREDIT_MEMO'",BigDecimal.class,key));
            if(vat.add(priorVat).compareTo(n(h.get("vat_amount")))>0) throw bad("VAT giảm vượt VAT hóa đơn.");
            amounts.put("VAT",vat.negate()); BigDecimal net=r.amount().subtract(vat);
            BigDecimal priorNet=n(db.queryForObject("SELECT coalesce(sum(-amount-vat_amount),0) FROM purchase_vendor_entries WHERE invoice_id=? AND entry_type='CREDIT_MEMO'",BigDecimal.class,key));
            if(net.add(priorNet).compareTo(n(h.get("total_amount")).subtract(n(h.get("vat_amount"))))>0) throw bad("Giá trị giảm trước thuế vượt giá trị trước thuế còn lại của hóa đơn; kiểm tra VAT giảm.");
            if(r.returnOrderId()!=null) {
                var ret=header(r.returnOrderId()); state(ret,"RETURN_ORDER","POSTED");
                if(!h.get("vendor_id").equals(ret.get("vendor_id")) || !h.get("currency_code").equals(ret.get("currency_code"))) throw bad("Return Order khác nhà cung cấp hoặc tiền tệ.");
                var credited=db.queryForMap("SELECT coalesce(sum(-amount),0) AS amount,coalesce(sum(return_cost),0) AS cost FROM purchase_vendor_entries WHERE return_order_id=?",r.returnOrderId());
                BigDecimal remaining=n(ret.get("total_amount")).subtract(n(credited.get("amount")));
                if(r.amount().compareTo(remaining)>0) throw bad("Credit Memo vượt giá trị đơn trả hàng chưa điều chỉnh.");
                if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM purchase_vendor_entries WHERE return_order_id=? AND invoice_id<>?)",Boolean.class,r.returnOrderId(),key))) throw bad("Return Order đã được áp dụng vào hóa đơn khác.");
                if(Boolean.TRUE.equals(db.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM (
                      SELECT item_id,sum(quantity) quantity FROM purchase_lines WHERE purchase_header_id=?
                       OR purchase_header_id IN(SELECT return_order_id FROM purchase_vendor_entries WHERE invoice_id=? AND return_order_id IS NOT NULL)
                      GROUP BY item_id) r
                      WHERE r.quantity>coalesce((SELECT sum(i.quantity) FROM purchase_lines i WHERE i.purchase_header_id=? AND i.item_id=r.item_id),0))
                    """,Boolean.class,r.returnOrderId(),key,key))) throw bad("Tổng số lượng trả vượt số lượng mặt hàng trên hóa đơn.");
                BigDecimal cost=db.queryForObject("SELECT inventory_value FROM purchase_return_shipments WHERE return_order_id=?",BigDecimal.class,r.returnOrderId());
                if(cost==null) throw bad("Return Order chưa có lịch sử gửi trả.");
                returnCost=r.amount().compareTo(remaining)==0?cost.subtract(n(credited.get("cost"))):cost.multiply(r.amount()).divide(n(ret.get("total_amount")),2,RoundingMode.HALF_UP);
                amounts.put("RETURN_CLEARING",returnCost.negate()); amounts.put("ADJUSTMENT",returnCost.subtract(net));
            } else amounts.put("ADJUSTMENT",net.negate());
        }
        gl(key,h,no,credit?"PURCHASE_CREDIT":"PURCHASE_PAYMENT",day,amounts);
        db.update("INSERT INTO purchase_vendor_entries(invoice_id,vendor_id,entry_type,document_no,posting_date,currency_code,amount,reference,return_order_id,request_key,vat_amount,return_cost) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",key,h.get("vendor_id"),type,no,java.sql.Date.valueOf(day),h.get("currency_code"),r.amount().negate(),r.reference(),r.returnOrderId(),r.requestKey(),n(r.vatAmount()),returnCost);
        return result(key);
    }
    @PostMapping("/{key}/refund") @Transactional
    public Map<String,Object> refund(@PathVariable long key,@RequestBody Settlement r) {
        validateSettlement(r); serialize();var h=header(key);state(h,"INVOICE","POSTED");
        if(n(r.vatAmount()).signum()!=0 || r.returnOrderId()!=null) throw bad("Hoàn tiền không có VAT hoặc Return Order.");
        var retry=db.queryForList("SELECT * FROM purchase_vendor_entries WHERE request_key=?",r.requestKey());
        if(!retry.isEmpty()) {
            var old=retry.getFirst();if(id(old.get("invoice_id"))!=key || !"REFUND".equals(old.get("entry_type")) || n(old.get("amount")).compareTo(r.amount())!=0 || !s(old.get("reference")).equals(r.reference())) throw bad("Mã yêu cầu đã dùng cho nội dung khác.");
            return result(key);
        }
        if(r.amount().compareTo(balance(key).negate())>0) throw bad("Hoàn tiền vượt số nhà cung cấp còn phải trả lại.");
        String no="P-RF-"+r.requestKey();LocalDate day=date(r.postingDate());
        gl(key,h,no,"PURCHASE_REFUND",day,Map.of("BANK",r.amount(),"PAYABLE",r.amount().negate()));
        db.update("INSERT INTO purchase_vendor_entries(invoice_id,vendor_id,entry_type,document_no,posting_date,currency_code,amount,reference,request_key) VALUES(?,?,'REFUND',?,?,?,?,?,?)",key,h.get("vendor_id"),no,java.sql.Date.valueOf(day),h.get("currency_code"),r.amount(),r.reference(),r.requestKey());
        return result(key);
    }
    private void gl(long key,Map<String,Object> h,String no,String type,LocalDate day,Map<String,BigDecimal> amounts) {
        if(amounts.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.ZERO)!=0) throw bad("Bút toán không cân bằng Nợ/Có.");
        if(!"VND".equals(h.get("currency_code"))) throw bad("G/L hiện dùng VND; cần cấu hình tỷ giá trước khi đăng chứng từ ngoại tệ.");
        for(var a:amounts.entrySet()) {
            if(a.getValue().signum()==0) continue;
            var rows=db.queryForList("SELECT p.account_id FROM purchase_posting_setup p JOIN accounts a ON a.id=p.account_id WHERE p.role=? AND a.active=true",a.getKey());
            if(rows.isEmpty()) throw bad("Chưa cấu hình tài khoản hạch toán Purchase: "+a.getKey()+". Mở Cấu hình hạch toán.");
            long ledger=db.queryForObject("INSERT INTO general_ledger(document_no,document_type,posting_date,account_id,description,debit,credit) VALUES(?,?,?,?,?,?,?) RETURNING id",Long.class,no,type,java.sql.Date.valueOf(day),rows.getFirst().get("account_id"),"Purchase "+h.get("document_no"),a.getValue().max(BigDecimal.ZERO),a.getValue().negate().max(BigDecimal.ZERO));
            db.update("INSERT INTO purchase_gl_links VALUES(?,?,?)",ledger,key,h.get("currency_code"));
        }
    }
}
