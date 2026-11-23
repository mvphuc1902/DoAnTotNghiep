package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/erp/order-planning")
public class OrderPlanningController {
 private final JdbcTemplate db;
 private final ErpWriteController writer;
 private final ObjectMapper json = new ObjectMapper();
 public OrderPlanningController(JdbcTemplate db, ErpWriteController writer) { this.db=db; this.writer=writer; }
 public record Selection(long itemId,long locationId,BigDecimal quantity) {}
 public record MakeRequest(UUID requestKey,List<Selection> lines) {}
 private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
 private static BigDecimal n(Object v) { return v==null?BigDecimal.ZERO:new BigDecimal(v.toString()); }
 private static long id(Object v) { return Long.parseLong(v.toString()); }
 static BigDecimal shortage(BigDecimal demand,BigDecimal available,BigDecimal incoming) {
  return demand.subtract(available).subtract(incoming).max(BigDecimal.ZERO);
 }
 @GetMapping @Transactional(readOnly=true)
 public List<Map<String,Object>> calculate() {
  var rows=db.queryForList("""
   WITH demand_sources AS (
    SELECT l.item_id,o.location_id,greatest(l.quantity-coalesce(l.quantity_shipped,0),0) AS quantity,
     o.order_no AS document_no,'Sales'::text AS demand_type
    FROM sales_orders o JOIN sales_order_lines l ON l.sales_order_id=o.id
    WHERE o.ship_on_post AND o.released_at IS NOT NULL AND o.status NOT IN ('CANCELLED','CLOSED')
    UNION ALL
    SELECT b.component_item_id,p.location_id,b.quantity*p.quantity,p.production_no,'Production'
    FROM production_orders p JOIN item_bom b ON b.parent_item_id=p.bom_parent_item_id
    JOIN items parent ON parent.id=p.bom_parent_item_id
    WHERE p.status='DRAFT' AND NOT coalesce(parent.blocked,false)
   ), demand AS (
    SELECT item_id,location_id,sum(quantity) AS demand,
     string_agg(DISTINCT document_no,', ' ORDER BY document_no) AS sales_orders,
     string_agg(DISTINCT demand_type,' + ' ORDER BY demand_type) AS demand_type
    FROM demand_sources WHERE quantity>0 GROUP BY item_id,location_id
   ), stock AS (
    SELECT item_id,location_id,sum(quantity) AS on_hand,sum(reserved_quantity) AS reserved,
     sum(quantity-reserved_quantity) AS available FROM inventory GROUP BY item_id,location_id
   ), incoming AS (
    SELECT p.item_id,coalesce(nullif(p.location_code,''),h.location_code) AS location_code,
     sum(greatest(p.quantity-coalesce(p.quantity_received,0),0)) AS incoming
    FROM purchase_lines p JOIN purchase_headers h ON h.id=p.purchase_header_id
    WHERE h.document_type='ORDER' AND h.status IN ('DRAFT','RELEASED')
    GROUP BY p.item_id,coalesce(nullif(p.location_code,''),h.location_code)
   )
   SELECT d.item_id::text AS item_id,d.location_id::text AS location_id,d.demand,d.sales_orders,d.demand_type,
    i.item_no,i.description,i.base_unit_of_measure,i.unit_cost,i.replenishment_system,
    i.preferred_vendor_id::text AS vendor_id,v.name AS vendor_name,v.blocked AS vendor_blocked,
    loc.code AS location_code,loc.name AS location_name,
    coalesce(s.on_hand,0) AS on_hand,coalesce(s.reserved,0) AS reserved,
    greatest(coalesce(s.available,0)-warehouse_unavailable_quantity(d.item_id,d.location_id),0) AS available,coalesce(p.incoming,0) AS incoming
   FROM demand d JOIN items i ON i.id=d.item_id JOIN locations loc ON loc.id=d.location_id
   LEFT JOIN vendors v ON v.id=i.preferred_vendor_id
   LEFT JOIN stock s ON s.item_id=d.item_id AND s.location_id=d.location_id
   LEFT JOIN incoming p ON p.item_id=d.item_id AND p.location_code=loc.code
   WHERE i.item_category='INVENTORY' AND NOT coalesce(i.blocked,false) AND d.demand>0
   ORDER BY i.item_no,loc.code
   """);
  for(var row:rows) {
   row.put("suggested",shortage(n(row.get("demand")),n(row.get("available")),n(row.get("incoming"))));
   row.put("can_purchase","PURCHASE".equals(row.get("replenishment_system")) && row.get("vendor_id")!=null && !Boolean.TRUE.equals(row.get("vendor_blocked")));
  }
  return rows;
 }
 @PostMapping("/make-orders") @Transactional
 public Map<String,Object> makeOrders(@RequestBody MakeRequest request) {
  if(request==null||request.requestKey()==null||request.lines()==null||request.lines().isEmpty()||request.lines().size()>500)throw bad("Chọn từ 1 đến 500 dòng đề xuất.");
  var keys=new HashSet<String>();
  for(var l:request.lines())if(l==null||l.itemId()<=0||l.locationId()<=0||l.quantity()==null||l.quantity().signum()<=0||l.quantity().stripTrailingZeros().scale()>3||!keys.add(l.itemId()+":"+l.locationId()))throw bad("Dòng đề xuất không hợp lệ hoặc bị trùng.");
  String signature=request.lines().stream().sorted(Comparator.comparingLong(Selection::itemId).thenComparingLong(Selection::locationId)).map(l->l.itemId()+":"+l.locationId()+":"+l.quantity().stripTrailingZeros().toPlainString()).toList().toString();
  db.execute("SELECT pg_advisory_xact_lock(7821348)");
  var old=db.queryForList("SELECT signature,result FROM order_planning_requests WHERE request_key=?",request.requestKey());
  if(!old.isEmpty()) {
   if(!signature.equals(old.getFirst().get("signature")))throw bad("Yêu cầu này đã dùng cho kế hoạch khác.");
   try { return Map.of("orders",json.readValue(old.getFirst().get("result").toString(),List.class)); }catch(Exception e){throw new IllegalStateException(e);}
  }
  var current=new HashMap<String,Map<String,Object>>();
  for(var row:calculate()) current.put(row.get("item_id")+":"+row.get("location_id"),row);
  var groups=new LinkedHashMap<String,List<Map<String,Object>>>();
  for(var selection:request.lines()) {
   var row=current.get(selection.itemId()+":"+selection.locationId());
   if(row==null||!Boolean.TRUE.equals(row.get("can_purchase"))||n(row.get("suggested")).compareTo(selection.quantity())!=0)throw bad("Nhu cầu/tồn kho/đơn mua đã thay đổi hoặc chưa cấu hình Purchase và Vendor. Hãy Calculate Plan lại.");
   groups.computeIfAbsent(row.get("vendor_id")+":"+row.get("location_id"),k->new ArrayList<>()).add(row);
  }
  var orders=new ArrayList<Map<String,Object>>();
  for(var group:groups.values()) {
   var first=group.getFirst();
   var header=new HashMap<String,Object>();
   header.put("document_type","ORDER");header.put("vendor_id",first.get("vendor_id"));
   header.put("order_date",LocalDate.now().toString());header.put("posting_date",LocalDate.now().toString());
   header.put("location_code",first.get("location_code"));header.put("currency_code","VND");
   header.put("notes","Order Planning: "+String.join("; ",group.stream().map(r->r.get("item_no")+" / "+r.get("sales_orders")).toList()));
   var lines=group.stream().map(r->new ErpWriteController.PurchaseDocumentLineInput(null,id(r.get("item_id")),Objects.toString(r.get("description"),""),Objects.toString(r.get("base_unit_of_measure"),""),n(r.get("suggested")),n(r.get("unit_cost")),BigDecimal.ZERO,BigDecimal.ZERO,r.get("location_code").toString())).toList();
   var created=writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(header,lines));
   orders.add(db.queryForMap("SELECT id::text AS id,document_no FROM purchase_headers WHERE id=?",id(created.get("id"))));
  }
  try { db.update("INSERT INTO order_planning_requests(request_key,signature,result) VALUES(?,?,?)",request.requestKey(),signature,json.writeValueAsString(orders)); }catch(java.io.IOException e){throw new IllegalStateException(e);}
  return Map.of("orders",orders);
 }
}
