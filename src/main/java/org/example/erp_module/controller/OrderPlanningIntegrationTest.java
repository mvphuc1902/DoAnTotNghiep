package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL",matches=".+")
class OrderPlanningIntegrationTest {
 static BigDecimal d(String s){return new BigDecimal(s);}
 @Test void shortageCreatesOnePurchaseOrderAndReceiptDoesNotDoubleCountSupply(){
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));
  var db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  tx.execute(status->{status.setRollbackOnly(); WarehouseTestSupport.ensureSchema(db);
   try { db.execute(Files.readString(Path.of("src/main/resources/order-planning-v1.sql"))); }catch(Exception e){throw new RuntimeException(e);}
   String suffix=UUID.randomUUID().toString().substring(0,8);
   long vendor=db.queryForObject("INSERT INTO vendors(vendor_no,name) VALUES(?,?) RETURNING id",Long.class,"PLAN-"+suffix,"Planning test vendor");
   long customer=db.queryForObject("INSERT INTO customers(customer_no,name,blocked) VALUES(?,?,false) RETURNING id",Long.class,"PLAN-"+suffix,"Planning test customer");
   long loc=db.queryForObject("INSERT INTO locations(code,name) VALUES(?,?) RETURNING id",Long.class,"PL-"+suffix,"Planning test warehouse");
   String unit=db.queryForObject("SELECT code FROM units_of_measure WHERE active=true ORDER BY code LIMIT 1",String.class);
   long item=db.queryForObject("INSERT INTO items(item_no,description,item_category,base_unit_of_measure,unit_cost,blocked,replenishment_system,preferred_vendor_id) VALUES(?,?,'INVENTORY',?,10,false,'PURCHASE',?) RETURNING id",Long.class,"PLAN-"+suffix,"Planning test item",unit,vendor);
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,d("30"),"TEST",item);
   long other=db.queryForObject("SELECT id FROM locations WHERE id<>? ORDER BY id LIMIT 1",Long.class,loc);
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,other,d("1000"),"TEST",item);
   long order=db.queryForObject("INSERT INTO sales_orders(order_no,customer_id,location_id,order_date,status,currency_code) VALUES(?,?,?,CURRENT_DATE,'OPEN','VND') RETURNING id",Long.class,"SO-PLAN-"+suffix,customer,loc);
   db.update("INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,description,quantity,quantity_shipped,quantity_invoiced,unit_price,discount_percent,discount_amount,vat_percent,vat_amount,line_amount) VALUES(?,10000,?,'Planning',100,0,0,10,0,0,0,0,1000)",order,item);
   var writer=new ErpWriteController(db);var plan=new OrderPlanningController(db,writer);
   assertTrue(plan.calculate().stream().noneMatch(r->r.get("item_id").equals(Long.toString(item))));
   new SalesShippingController(db).release(order);
   var row=plan.calculate().stream().filter(r->r.get("item_id").equals(Long.toString(item))).findFirst().orElseThrow();
   assertEquals(0,d("70").compareTo((BigDecimal)row.get("suggested")));
   var request=new OrderPlanningController.MakeRequest(UUID.randomUUID(),List.of(new OrderPlanningController.Selection(item,loc,d("70"))));
   var result=plan.makeOrders(request);
   assertEquals(result,plan.makeOrders(request));
   assertThrows(Exception.class,()->plan.makeOrders(new OrderPlanningController.MakeRequest(UUID.randomUUID(),request.lines())));
   long po=db.queryForObject("SELECT id FROM purchase_headers WHERE vendor_id=?",Long.class,vendor);
   assertTrue(db.queryForObject("SELECT document_no FROM purchase_headers WHERE id=?",String.class,po).startsWith("PO-"));
   assertEquals(0,d("30").compareTo(db.queryForObject("SELECT quantity FROM inventory WHERE item_id=? AND location_id=?",BigDecimal.class,item,loc)));
   long line=db.queryForObject("SELECT id FROM purchase_lines WHERE purchase_header_id=?",Long.class,po);
   var purchase=new PurchaseWorkflowController(db,writer);purchase.release(po);
   purchase.receive(po,new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(new PurchaseWorkflowController.ReceiveLine(line,d("20")))));
   row=plan.calculate().stream().filter(r->r.get("item_id").equals(Long.toString(item))).findFirst().orElseThrow();
   assertEquals(0,d("50").compareTo((BigDecimal)row.get("available")));
   assertEquals(0,d("50").compareTo((BigDecimal)row.get("incoming")));
   assertEquals(0,((BigDecimal)row.get("suggested")).signum());
   return null;
  });
 }
 @Test void arithmeticClampsSurplusAndIncludesExistingSupply(){
  assertEquals(d("70"),OrderPlanningController.shortage(d("100"),d("30"),d("0")));
  assertEquals(d("50"),OrderPlanningController.shortage(d("100"),d("30"),d("20")));
  assertEquals(BigDecimal.ZERO,OrderPlanningController.shortage(d("100"),d("120"),d("0")));
 }
}
