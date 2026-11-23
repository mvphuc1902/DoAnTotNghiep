package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController @RequestMapping("/api/erp/warehouse")
public class WarehouseController {
 private final JdbcTemplate db;
 public WarehouseController(JdbcTemplate db){this.db=db;}
 public record BinInput(long zoneId,String code,String type,BigDecimal capacity){}
 public record Action(UUID requestKey,String type,long itemId,long fromBinId,Long toBinId,BigDecimal quantity,String reason,Long snapshotEntryId,BigDecimal expectedQuantity){
  public Action(UUID requestKey,String type,long itemId,long fromBinId,Long toBinId,BigDecimal quantity,String reason){this(requestKey,type,itemId,fromBinId,toBinId,quantity,reason,null,null);}
 }
 private static ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
 private static BigDecimal n(Object x){return x==null?BigDecimal.ZERO:new BigDecimal(x.toString());}
 private static long id(Object x){return Long.parseLong(x.toString());}
 private void lock(){db.execute("SELECT pg_advisory_xact_lock(7821348)");}
 private String user(){var a=SecurityContextHolder.getContext().getAuthentication();return a==null?"SYSTEM":a.getName();}
 private void manager(){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||a.getAuthorities().stream().noneMatch(v->Set.of("ROLE_ADMIN","ROLE_DIRECTOR").contains(v.getAuthority())))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chỉ Admin/Director được cấu hình kho hoặc phê duyệt kiểm kê.");}
 private Map<String,Object> bin(long key){var rows=db.queryForList("SELECT b.*,l.code AS location_code FROM warehouse_bins b JOIN locations l ON l.id=b.location_id WHERE b.id=? FOR UPDATE OF b",key);if(rows.isEmpty()||Boolean.TRUE.equals(rows.getFirst().get("blocked")))throw bad("Bin không tồn tại hoặc đã khóa.");return rows.getFirst();}
 private void quantity(BigDecimal value,boolean zero){if(value==null||value.signum()<0||(!zero&&value.signum()==0)||value.stripTrailingZeros().scale()>3)throw bad("Số lượng không hợp lệ, tối đa 3 chữ số thập phân.");}
 @GetMapping("/locations/{location}") @Transactional(readOnly=true)
 public Map<String,Object> state(@PathVariable long location){return Map.of(
  "enabled",db.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouse_config WHERE location_id=?)",Boolean.class,location),
  "zones",db.queryForList("SELECT id::text AS id,code,name FROM warehouse_zones WHERE location_id=? ORDER BY code",location),
  "bins",db.queryForList("SELECT id::text AS id,code,bin_type,capacity,blocked FROM warehouse_bins WHERE location_id=? ORDER BY code",location),
  "stock",db.queryForList("SELECT s.bin_id::text AS bin_id,b.code AS bin_code,b.bin_type,s.item_id::text AS item_id,i.item_no,i.description,i.base_unit_of_measure,s.quantity FROM warehouse_bin_inventory s JOIN warehouse_bins b ON b.id=s.bin_id JOIN items i ON i.id=s.item_id WHERE s.location_id=? ORDER BY b.code,i.item_no",location),
  "counts",db.queryForList("SELECT o.id::text AS id,o.document_no,o.status,i.item_no,b.code AS bin_code,o.expected_quantity,o.counted_quantity FROM warehouse_operations o JOIN warehouse_bins b ON b.id=o.source_bin_id JOIN items i ON i.id=o.item_id WHERE b.location_id=? AND o.operation_type='COUNT' AND o.status IN ('PENDING','APPROVED') ORDER BY o.id",location));}
 @PostMapping("/locations/{location}/enable") @Transactional
 public void enable(@PathVariable long location){manager();lock();
  if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM locations WHERE id=?)",Boolean.class,location)))throw bad("Kho không tồn tại.");
  if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouse_config WHERE location_id=?)",Boolean.class,location)))return;
  if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inventory WHERE location_id=? AND product_id IS NOT NULL AND quantity<>0)",Boolean.class,location)))throw bad("Kho còn tồn Product cũ. Đối chiếu/chuyển dữ liệu sang Item trước khi bật Bin.");
  long zone=db.queryForObject("INSERT INTO warehouse_zones(location_id,code,name) VALUES(?,'DEFAULT','Khu vực mặc định') RETURNING id",Long.class,location);
  for(var pair:List.of(new String[]{"RECEIVE","RECEIVING"},new String[]{"STORAGE","STORAGE"},new String[]{"SHIP","SHIPPING"},new String[]{"QC","QC"},new String[]{"QUARANTINE","QUARANTINE"},new String[]{"RETURN","RETURN"}))db.update("INSERT INTO warehouse_bins(location_id,zone_id,code,bin_type) VALUES(?,?,?,?)",location,zone,pair[0],pair[1]);
  long storage=db.queryForObject("SELECT id FROM warehouse_bins WHERE location_id=? AND code='STORAGE'",Long.class,location);
  db.update("INSERT INTO warehouse_entries(location_id,bin_id,item_id,quantity,entry_type) SELECT location_id,?,item_id,quantity,'OPENING' FROM inventory WHERE location_id=? AND item_id IS NOT NULL AND quantity>0",storage,location);
  db.update("INSERT INTO warehouse_config(location_id) VALUES(?)",location);
 }
 @PostMapping("/locations/{location}/zones") @Transactional
 public void zone(@PathVariable long location,@RequestBody Map<String,String> input){manager();lock();String code=input.getOrDefault("code","").trim(),name=input.getOrDefault("name","").trim();if(code.isEmpty()||code.length()>30||name.isEmpty()||name.length()>150)throw bad("Nhập mã Zone tối đa 30 ký tự và tên tối đa 150 ký tự.");if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouse_config WHERE location_id=?)",Boolean.class,location)))throw bad("Bật quản lý Bin trước.");db.update("INSERT INTO warehouse_zones(location_id,code,name) VALUES(?,?,?)",location,code,name);}
 @PostMapping("/locations/{location}/bins") @Transactional
 public void createBin(@PathVariable long location,@RequestBody BinInput input){manager();lock();if(input==null||input.code()==null||input.code().isBlank()||input.code().length()>30||!Set.of("RECEIVING","STORAGE","PICKING","SHIPPING","QC","QUARANTINE","RETURN").contains(input.type()))throw bad("Mã/loại Bin không hợp lệ.");if(input.capacity()!=null)quantity(input.capacity(),false);if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouse_zones WHERE id=? AND location_id=?)",Boolean.class,input.zoneId(),location)))throw bad("Zone không thuộc kho.");db.update("INSERT INTO warehouse_bins(location_id,zone_id,code,bin_type,capacity) VALUES(?,?,?,?,?)",location,input.zoneId(),input.code().trim(),input.type(),input.capacity());}
 @GetMapping("/snapshot") @Transactional(readOnly=true)
 public Map<String,Object> snapshot(@RequestParam long binId,@RequestParam long itemId){
  if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouse_bins WHERE id=? AND NOT blocked)",Boolean.class,binId)))throw bad("Bin không tồn tại hoặc bị khóa.");
  return db.queryForMap("SELECT coalesce((SELECT quantity FROM warehouse_bin_inventory WHERE bin_id=? AND item_id=?),0) AS quantity,coalesce((SELECT max(id) FROM warehouse_entries WHERE bin_id=? AND item_id=?),0)::text AS revision",binId,itemId,binId,itemId);
 }
 @PostMapping("/bins/{key}/config") @Transactional
 public void configureBin(@PathVariable long key,@RequestBody Map<String,Object> input){manager();lock();var rows=db.queryForList("SELECT * FROM warehouse_bins WHERE id=? FOR UPDATE",key);if(rows.isEmpty())throw bad("Bin không tồn tại.");BigDecimal capacity=input.get("capacity")==null?null:n(input.get("capacity"));if(capacity!=null){quantity(capacity,false);if(capacity.compareTo(db.queryForObject("SELECT coalesce(sum(quantity),0) FROM warehouse_bin_inventory WHERE bin_id=?",BigDecimal.class,key))<0)throw bad("Sức chứa không được nhỏ hơn số lượng đang có trong Bin.");}if(!(input.get("blocked") instanceof Boolean))throw bad("Trạng thái khóa không hợp lệ.");db.update("UPDATE warehouse_bins SET capacity=?,blocked=? WHERE id=?",capacity,input.get("blocked"),key);}
 @PostMapping("/register") @Transactional
 public Map<String,Object> register(@RequestBody Action a){
  if(a==null||a.requestKey()==null||!Set.of("PUTAWAY","PICK","MOVE","TRANSFER","COUNT").contains(Objects.toString(a.type(),""))||a.itemId()<=0||a.fromBinId()<=0||a.reason()==null||a.reason().isBlank()||a.reason().length()>1000)throw bad("Thiếu thông tin thao tác và lý do.");quantity(a.quantity(),"COUNT".equals(a.type()));lock();
  String signature=List.of(a.type(),Long.toString(a.itemId()),Long.toString(a.fromBinId()),Objects.toString(a.toBinId(),""),a.quantity().stripTrailingZeros().toPlainString(),a.reason(),Objects.toString(a.snapshotEntryId(),""),Objects.toString(a.expectedQuantity(),"")).toString();
  var prior=db.queryForList("SELECT id::text AS id,document_no,signature FROM warehouse_operations WHERE request_key=?",a.requestKey());if(!prior.isEmpty()){if(!signature.equals(prior.getFirst().get("signature")))throw bad("Mã yêu cầu đã được dùng cho nội dung khác.");return prior.getFirst();}
  if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM items WHERE id=? AND item_category='INVENTORY' AND NOT coalesce(blocked,false))",Boolean.class,a.itemId())))throw bad("Item phải là Inventory còn hoạt động.");
  var source=bin(a.fromBinId());Map<String,Object> destination=null;
  if(!"COUNT".equals(a.type())){
   if(a.toBinId()==null||a.toBinId()==a.fromBinId())throw bad("Chọn Bin đích khác Bin nguồn.");destination=bin(a.toBinId());
   boolean same=Objects.equals(source.get("location_id"),destination.get("location_id"));if("TRANSFER".equals(a.type())?same:!same)throw bad("Movement/Put-away/Pick cùng kho; Transfer phải khác kho.");
   if("PUTAWAY".equals(a.type())&&(!Set.of("RECEIVING","RETURN","QC").contains(source.get("bin_type"))||!Set.of("STORAGE","PICKING","QC","QUARANTINE").contains(destination.get("bin_type"))))throw bad("Put-away: từ Receiving/Return/QC sang Storage/Picking/QC/Quarantine.");
   if("PICK".equals(a.type())&&(!Set.of("STORAGE","PICKING").contains(source.get("bin_type"))||!"SHIPPING".equals(destination.get("bin_type"))))throw bad("Pick: từ Storage/Picking sang Shipping.");
   if(Set.of("QC","QUARANTINE").contains(source.get("bin_type")))manager();
  }
  BigDecimal current=db.queryForObject("SELECT coalesce(sum(quantity),0) FROM warehouse_bin_inventory WHERE bin_id=? AND item_id=?",BigDecimal.class,a.fromBinId(),a.itemId());
  if(!"COUNT".equals(a.type())&&current.compareTo(a.quantity())<0)throw bad("Không đủ tồn Bin nguồn.");
  String no=db.queryForObject("SELECT 'WH-'||lpad(n::text,greatest(3,length(n::text)),'0') FROM (SELECT nextval('warehouse_operation_no_seq') AS n) seq",String.class);
  Long snapshot=db.queryForObject("SELECT coalesce(max(id),0) FROM warehouse_entries WHERE bin_id=? AND item_id=?",Long.class,a.fromBinId(),a.itemId());
  if("COUNT".equals(a.type()) && (a.snapshotEntryId()==null || a.expectedQuantity()==null || a.snapshotEntryId().longValue()!=snapshot || a.expectedQuantity().compareTo(current)!=0))throw bad("Tồn kho đã thay đổi hoặc chưa chốt số hệ thống. Chọn lại Bin/Item và kiểm đếm lại.");
  long op=db.queryForObject("INSERT INTO warehouse_operations(document_no,operation_type,item_id,source_bin_id,destination_bin_id,quantity,status,expected_quantity,counted_quantity,snapshot_entry_id,reason,created_by,request_key,signature) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,no,a.type(),a.itemId(),a.fromBinId(),a.toBinId(),a.quantity(),"COUNT".equals(a.type())?"PENDING":"REGISTERED",current,"COUNT".equals(a.type())?a.quantity():null,snapshot,a.reason(),user(),a.requestKey(),signature);
  if("TRANSFER".equals(a.type())){
   BigDecimal sourceCost=db.queryForObject("SELECT average_cost FROM inventory WHERE item_id=? AND location_id=?",BigDecimal.class,a.itemId(),source.get("location_id"));
   db.update("INSERT INTO inventory(item_id,location_id,quantity,average_cost) VALUES(?,?,0,?) ON CONFLICT(item_id,location_id) DO NOTHING",a.itemId(),destination.get("location_id"),sourceCost);
   var priorStock=db.queryForMap("SELECT quantity,average_cost FROM inventory WHERE item_id=? AND location_id=? FOR UPDATE",a.itemId(),destination.get("location_id"));
   BigDecimal weighted=n(priorStock.get("quantity")).multiply(n(priorStock.get("average_cost"))).add(a.quantity().multiply(sourceCost)).divide(n(priorStock.get("quantity")).add(a.quantity()),6,java.math.RoundingMode.HALF_UP);
   db.update("UPDATE inventory SET average_cost=? WHERE item_id=? AND location_id=?",sourceCost,a.itemId(),destination.get("location_id"));
   stock(a.itemId(),id(source.get("location_id")),a.fromBinId(),a.quantity().negate(),"WH_TRANSFER",op);
   stock(a.itemId(),id(destination.get("location_id")),a.toBinId(),a.quantity(),"WH_TRANSFER",op);
   db.update("UPDATE inventory SET average_cost=? WHERE item_id=? AND location_id=?",weighted,a.itemId(),destination.get("location_id"));
  }else if(!"COUNT".equals(a.type())){
   entry(source,a.itemId(),a.quantity().negate(),a.type(),op);entry(destination,a.itemId(),a.quantity(),a.type(),op);
  }
  if(!"COUNT".equals(a.type()))db.update("UPDATE warehouse_operations SET posted_at=now() WHERE id=?",op);
  return Map.of("id",Long.toString(op),"document_no",no);
 }
 private void entry(Map<String,Object> bin,long item,BigDecimal qty,String type,long operation){db.update("INSERT INTO warehouse_entries(location_id,bin_id,item_id,quantity,entry_type,operation_id) VALUES(?,?,?,?,?,?)",bin.get("location_id"),bin.get("id"),item,qty,type,operation);}
 private void stock(long item,long location,long bin,BigDecimal qty,String type,long op){db.queryForObject("SELECT set_config('erp.warehouse_bin',?,true)",String.class,Long.toString(bin));db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,location,qty,type,op);db.execute("SELECT set_config('erp.warehouse_bin','',true)");}
 @PostMapping("/counts/{key}/approve") @Transactional
 public void approve(@PathVariable long key){manager();lock();var row=count(key);if("PENDING".equals(row.get("status")))db.update("UPDATE warehouse_operations SET status='APPROVED',approved_by=? WHERE id=?",user(),key);}
 private Map<String,Object> count(long key){var rows=db.queryForList("SELECT * FROM warehouse_operations WHERE id=? AND operation_type='COUNT' FOR UPDATE",key);if(rows.isEmpty())throw bad("Phiếu kiểm kê không tồn tại.");return rows.getFirst();}
 @PostMapping("/counts/{key}/post") @Transactional
 public void postCount(@PathVariable long key){manager();lock();var row=count(key);if("POSTED".equals(row.get("status")))return;if(!"APPROVED".equals(row.get("status")))throw bad("Phê duyệt phiếu kiểm kê trước.");long item=id(row.get("item_id")),binId=id(row.get("source_bin_id"));var bin=bin(binId);
  Long revision=db.queryForObject("SELECT coalesce(max(id),0) FROM warehouse_entries WHERE bin_id=? AND item_id=?",Long.class,binId,item);if(revision!=id(row.get("snapshot_entry_id")))throw bad("Bin đã thay đổi từ lúc kiểm kê. Tạo phiếu kiểm kê mới.");
  BigDecimal delta=n(row.get("counted_quantity")).subtract(n(row.get("expected_quantity")));
  BigDecimal cost=db.queryForObject("SELECT coalesce((SELECT average_cost FROM inventory WHERE item_id=? AND location_id=?),(SELECT unit_cost FROM items WHERE id=?),0)",BigDecimal.class,item,bin.get("location_id"),item);
  BigDecimal value=delta.multiply(cost).setScale(2,java.math.RoundingMode.HALF_UP);
  if(value.signum()!=0){
   for(String role:List.of("INVENTORY","ADJUSTMENT")){
    var accounts=db.queryForList("SELECT s.account_id FROM purchase_posting_setup s JOIN accounts a ON a.id=s.account_id WHERE s.role=? AND a.active=true",role);
    if(accounts.isEmpty())throw bad("Thiếu tài khoản hạch toán "+role+". Cấu hình tài khoản trước khi Post kiểm kê.");
    BigDecimal signed=role.equals("INVENTORY")?value:value.negate();
    long ledger=db.queryForObject("INSERT INTO general_ledger(document_no,document_type,posting_date,account_id,description,debit,credit) VALUES(?,'WH_COUNT',CURRENT_DATE,?,?,?,?) RETURNING id",Long.class,row.get("document_no"),accounts.getFirst().get("account_id"),"Kiểm kê kho VND: "+row.get("reason"),signed.max(BigDecimal.ZERO),signed.negate().max(BigDecimal.ZERO));
    db.update("INSERT INTO warehouse_gl_links(operation_id,ledger_id) VALUES(?,?)",key,ledger);
   }
  }
  stock(item,id(bin.get("location_id")),binId,delta,"WH_COUNT",key);db.update("UPDATE warehouse_operations SET status='POSTED',posted_at=now() WHERE id=?",key);
 }
}
