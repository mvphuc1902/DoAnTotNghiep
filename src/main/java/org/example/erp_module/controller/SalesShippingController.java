package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController @RequestMapping("/api/erp/sales-shipping")
public class SalesShippingController {
 private final JdbcTemplate db;
 public SalesShippingController(JdbcTemplate db){this.db=db;}
 public record Line(long lineId,BigDecimal quantity){}
 public record Request(UUID requestKey,LocalDate postingDate,List<Line> lines){}
 private static ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
 private void mutex(){db.execute("SELECT pg_advisory_xact_lock(7821348)");}
 private Map<String,Object> order(long id){var rows=db.queryForList("SELECT o.*,l.code AS location_code FROM sales_orders o LEFT JOIN locations l ON l.id=o.location_id WHERE o.id=? FOR UPDATE OF o",id);if(rows.isEmpty())throw bad("Không tìm thấy Sales Order.");return rows.getFirst();}
 private void eligible(Map<String,Object> row){if(!Boolean.TRUE.equals(row.get("ship_on_post")))throw bad("Đơn cũ dùng lịch sử xuất kho trước đây; không được xuất lại.");if("CANCELLED".equals(row.get("status")))throw bad("Đơn đã hủy.");if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM customers WHERE id=? AND NOT coalesce(blocked,false))",Boolean.class,row.get("customer_id"))))throw bad("Khách hàng không tồn tại hoặc bị khóa.");}
 @GetMapping("/{id}") @Transactional(readOnly=true)
 public Map<String,Object> state(@PathVariable long id){
  var h=db.queryForList("SELECT ship_on_post,released_at FROM sales_orders WHERE id=?",id);if(h.isEmpty())throw bad("Không tìm thấy đơn.");
  return Map.of("deferred",h.getFirst().get("ship_on_post"),"released",h.getFirst().get("released_at")!=null,
   "lines",db.queryForList("SELECT l.id,i.item_no,l.description,l.quantity,l.quantity_shipped,coalesce((SELECT sum(quantity) FROM sales_invoice_lines WHERE sales_order_line_id=l.id),0) AS invoiced FROM sales_order_lines l JOIN items i ON i.id=l.item_id WHERE l.sales_order_id=? ORDER BY l.line_no",id),
   "shipments",db.queryForList("SELECT id,shipment_no,shipment_date FROM sales_shipments WHERE sales_order_id=? ORDER BY id",id));
 }
 @PostMapping("/{id}/release") @Transactional
 public void release(@PathVariable long id){mutex();var h=order(id);eligible(h);if(h.get("released_at")!=null)return;if(h.get("location_id")==null)throw bad("Chọn kho trước khi Release.");if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sales_order_lines WHERE sales_order_id=?)",Boolean.class,id)))throw bad("Đơn chưa có dòng hàng.");db.update("UPDATE sales_orders SET released_at=CURRENT_TIMESTAMP WHERE id=?",id);}
 static void validate(Request r){if(r==null||r.requestKey()==null||r.lines()==null||r.lines().isEmpty()||r.lines().size()>500)throw bad("Chọn từ 1 đến 500 dòng giao hàng.");var unique=new HashSet<Long>();for(var l:r.lines())if(l==null||l.lineId()<=0||!unique.add(l.lineId())||l.quantity()==null||l.quantity().signum()<=0||l.quantity().stripTrailingZeros().scale()>3)throw bad("Số lượng giao không hợp lệ hoặc dòng bị trùng.");}
 @PostMapping("/{id}/ship") @Transactional
 public Map<String,Object> ship(@PathVariable long id,@RequestBody Request r){
  validate(r);mutex();var h=order(id);eligible(h);if(h.get("released_at")==null)throw bad("Release đơn trước khi giao hàng.");
  String signature=Objects.toString(r.postingDate(),"")+r.lines().stream().sorted(Comparator.comparingLong(Line::lineId)).map(l->l.lineId()+":"+l.quantity().stripTrailingZeros().toPlainString()).toList();
  var previous=db.queryForList("SELECT id,sales_order_id,request_signature FROM sales_shipments WHERE request_key=?",r.requestKey());
  if(!previous.isEmpty()){var old=previous.getFirst();if(((Number)old.get("sales_order_id")).longValue()!=id||!signature.equals(old.get("request_signature")))throw bad("Mã yêu cầu đã dùng cho nội dung khác.");return Map.of("id",old.get("id"));}
  var selected=new LinkedHashMap<Line,Map<String,Object>>();
  for(var requested:r.lines()){
   var rows=db.queryForList("SELECT l.*,i.item_category,i.blocked FROM sales_order_lines l JOIN items i ON i.id=l.item_id WHERE l.id=? AND l.sales_order_id=? FOR UPDATE OF l",requested.lineId(),id);
   if(rows.isEmpty())throw bad("Dòng không thuộc đơn này.");var l=rows.getFirst();if(Boolean.TRUE.equals(l.get("blocked")))throw bad("Item bị khóa.");
   if(requested.quantity().compareTo(((BigDecimal)l.get("quantity")).subtract((BigDecimal)l.get("quantity_shipped")))>0)throw bad("Giao vượt số lượng còn lại.");selected.put(requested,l);
  }
  String no=db.queryForObject("SELECT erp_next_document_no('SS')",String.class);
  long shipment=db.queryForObject("INSERT INTO sales_shipments(shipment_no,sales_order_id,customer_id,shipment_date,location_code,request_key,request_signature) VALUES(?,?,?,?,?,?,?) RETURNING id",Long.class,no,id,h.get("customer_id"),java.sql.Date.valueOf(r.postingDate()==null?LocalDate.now():r.postingDate()),h.get("location_code"),r.requestKey(),signature);
  db.execute("SELECT set_config('erp.sales_shipping','on',true)");int seq=10000;
  for(var e:selected.entrySet()){
   var rline=e.getKey();var l=e.getValue();
   if("INVENTORY".equals(l.get("item_category"))){
    db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,l.get("item_id"),h.get("location_id"),rline.quantity().negate(),"sales_shipments",shipment);
    db.update("INSERT INTO erp_stock_lines(source_table,source_id,item_id,location_id,applied_quantity) VALUES('sales_order_lines',?,?,?,?) ON CONFLICT(source_table,source_id) DO UPDATE SET applied_quantity=erp_stock_lines.applied_quantity+excluded.applied_quantity",rline.lineId(),l.get("item_id"),h.get("location_id"),rline.quantity().negate());
   }
   db.update("INSERT INTO sales_shipment_lines(shipment_id,line_no,sales_order_line_id,item_id,quantity,unit_of_measure,location_code) VALUES(?,?,?,?,?,?,?)",shipment,seq,rline.lineId(),l.get("item_id"),rline.quantity(),l.get("unit_of_measure"),h.get("location_code"));seq+=10000;
   db.update("UPDATE sales_order_lines SET quantity_shipped=quantity_shipped+? WHERE id=?",rline.quantity(),rline.lineId());
  }
  db.execute("SELECT set_config('erp.sales_shipping','off',true)");return Map.of("id",shipment,"documentNo",no);
 }
}
