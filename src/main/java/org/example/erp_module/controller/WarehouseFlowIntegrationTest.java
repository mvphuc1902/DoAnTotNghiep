package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL",matches=".+")
class WarehouseFlowIntegrationTest {
 static BigDecimal d(String x){return new BigDecimal(x);}
 @Test void receiptPutawayPickTransferCountAndLedgerStayReconciled(){
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));var db=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);var nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("warehouse-test","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  try{tx.execute(status->{status.setRollbackOnly();WarehouseTestSupport.ensureSchema(db);
   String code="WH-"+UUID.randomUUID().toString().substring(0,8);
   long loc=db.queryForObject("INSERT INTO locations(code,name) VALUES(?,?) RETURNING id",Long.class,code,code),dest=db.queryForObject("INSERT INTO locations(code,name) VALUES(?,?) RETURNING id",Long.class,code+"D",code);
   long item=db.queryForObject("INSERT INTO items(item_no,description,item_category,unit_cost,blocked) VALUES(?,?,'INVENTORY',10,false) RETURNING id",Long.class,code,code);
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,d("30"),"TEST",item);db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,dest,d("10"),"TEST",item);db.update("UPDATE inventory SET average_cost=20 WHERE item_id=? AND location_id=?",item,dest);
   var flow=new WarehouseController(db);flow.enable(loc);flow.enable(loc);flow.enable(dest);
   long receive=bin(db,loc,"RECEIVE"),storage=bin(db,loc,"STORAGE"),ship=bin(db,loc,"SHIP"),qc=bin(db,loc,"QC"),target=bin(db,dest,"STORAGE");
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,d("100"),"TEST_RECEIPT",item);
   var put=action("PUTAWAY",item,receive,storage,"98");assertEquals(flow.register(put).get("id"),flow.register(put).get("id"));flow.register(action("PUTAWAY",item,receive,qc,"2"));
   assertEquals(0,d("130").compareTo(stock(db,item,loc)));assertEquals(0,d("2").compareTo(db.queryForObject("SELECT warehouse_unavailable_quantity(?,?)",BigDecimal.class,item,loc)));
   assertThrows(Exception.class,()->nested.execute(s->{db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,d("-10"),"sales_shipments",item);return null;}));assertEquals(0,d("130").compareTo(stock(db,item,loc)));
   flow.register(action("PICK",item,storage,ship,"10"));db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,d("-10"),"sales_shipments",item);
   int count=db.queryForObject("SELECT count(*) FROM inventory_ledger WHERE item_id=?",Integer.class,item);flow.register(action("MOVE",item,storage,qc,"1"));assertEquals(count,db.queryForObject("SELECT count(*) FROM inventory_ledger WHERE item_id=?",Integer.class,item));
   flow.register(action("TRANSFER",item,storage,target,"5"));assertEquals(0,d("15").compareTo(stock(db,item,dest)));assertEquals(0,d("16.67").compareTo(db.queryForObject("SELECT average_cost FROM inventory WHERE item_id=? AND location_id=?",BigDecimal.class,item,dest).setScale(6)));
   long stale=Long.parseLong(flow.register(count(flow,item,storage,"110")).get("id").toString());flow.approve(stale);flow.register(action("MOVE",item,storage,qc,"1"));flow.register(action("MOVE",item,qc,storage,"1"));assertThrows(Exception.class,()->flow.postCount(stale));
   long current=Long.parseLong(flow.register(count(flow,item,storage,"110")).get("id").toString());assertThrows(Exception.class,()->flow.postCount(current));flow.approve(current);flow.postCount(current);flow.postCount(current);
   assertEquals(0,d("113").compareTo(stock(db,item,loc)));assertEquals(0,db.queryForObject("SELECT sum(g.debit-g.credit) FROM warehouse_gl_links k JOIN general_ledger g ON g.id=k.ledger_id WHERE k.operation_id=?",BigDecimal.class,current).signum());
   assertEquals(0,stock(db,item,loc).compareTo(db.queryForObject("SELECT sum(quantity) FROM warehouse_bin_inventory WHERE item_id=? AND location_id=?",BigDecimal.class,item,loc)));
   assertThrows(Exception.class,()->nested.execute(s->{db.update("DELETE FROM warehouse_entries WHERE item_id=?",item);return null;}));
   return null;
  });}finally{SecurityContextHolder.clearContext();}
 }
 static WarehouseController.Action count(WarehouseController flow,long item,long bin,String qty){var snapshot=flow.snapshot(bin,item);return new WarehouseController.Action(UUID.randomUUID(),"COUNT",item,bin,null,d(qty),"Rollback count",Long.parseLong(snapshot.get("revision").toString()),new BigDecimal(snapshot.get("quantity").toString()));}
 static long bin(JdbcTemplate db,long loc,String code){return db.queryForObject("SELECT id FROM warehouse_bins WHERE location_id=? AND code=?",Long.class,loc,code);}
 static BigDecimal stock(JdbcTemplate db,long item,long loc){return db.queryForObject("SELECT quantity FROM inventory WHERE item_id=? AND location_id=?",BigDecimal.class,item,loc);}
 static WarehouseController.Action action(String type,long item,long from,Long to,String qty){return new WarehouseController.Action(UUID.randomUUID(),type,item,from,to,d(qty),"Rollback warehouse test");}
}
