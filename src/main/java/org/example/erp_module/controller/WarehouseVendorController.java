package org.example.erp_module.controller;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/erp/master-summary")
@Transactional(readOnly=true)
public class WarehouseVendorController {
 private final JdbcTemplate db;
 public WarehouseVendorController(JdbcTemplate db){this.db=db;}
 @GetMapping("/locations/{id}")
 public Map<String,Object> warehouse(@PathVariable long id){
  var records=db.queryForList("SELECT id,code,name FROM locations WHERE id=?",id);
  if(records.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy kho.");
  return Map.of("warehouse",records.getFirst(),"stock",db.queryForList("""
   WITH incoming AS (
    SELECT p.item_id,sum(greatest(p.quantity-coalesce(p.quantity_received,0),0)) AS incoming
    FROM purchase_lines p JOIN purchase_headers h ON h.id=p.purchase_header_id
    WHERE h.document_type='ORDER' AND h.status IN ('DRAFT','RELEASED')
      AND coalesce(nullif(p.location_code,''),h.location_code)=?
    GROUP BY p.item_id
   ), stock AS (
    SELECT item_id,sum(quantity) AS quantity,sum(reserved_quantity) AS reserved
    FROM inventory WHERE location_id=? AND item_id IS NOT NULL GROUP BY item_id
   )
   SELECT i.item_no,i.description,i.base_unit_of_measure,
    coalesce(s.quantity,0) AS quantity,coalesce(s.reserved,0) AS reserved,
    greatest(coalesce(s.quantity,0)-coalesce(s.reserved,0)-warehouse_unavailable_quantity(i.id,?),0) AS available,coalesce(p.incoming,0) AS incoming
   FROM stock s FULL JOIN incoming p ON p.item_id=s.item_id
   JOIN items i ON i.id=coalesce(s.item_id,p.item_id) ORDER BY i.item_no
   """,records.getFirst().get("code"),id,id));
 }
 @GetMapping("/vendors/{id}")
 public Map<String,Object> vendor(@PathVariable long id){
  if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM vendors WHERE id=?)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy Vendor.");
  return Map.of("balances",db.queryForList("SELECT currency_code,sum(amount) AS balance FROM purchase_vendor_entries WHERE vendor_id=? GROUP BY currency_code ORDER BY currency_code",id),
   "documents",db.queryForList("SELECT document_type,status,currency_code,count(*) AS documents,sum(total_amount) AS total_amount FROM purchase_headers WHERE vendor_id=? GROUP BY document_type,status,currency_code ORDER BY document_type,status,currency_code",id));
 }
}
