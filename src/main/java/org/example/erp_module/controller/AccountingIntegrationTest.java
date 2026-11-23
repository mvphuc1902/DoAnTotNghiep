package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL",matches=".+")
class AccountingIntegrationTest {
 @Test void savingInvoicePostsOnceAndProductionCarriesMaterialCost() {
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));var db=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);var nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  tx.execute(status->{status.setRollbackOnly();try{if(db.queryForObject("SELECT to_regclass('sales_postings') IS NULL",Boolean.class))db.execute(Files.readString(Path.of("src/main/resources/erp-accounting-v1.sql")));}catch(Exception e){throw new RuntimeException(e);}
   String tag="AC-"+UUID.randomUUID().toString().substring(0,8);
   long loc=db.queryForObject("INSERT INTO locations(code,name) VALUES(?,?) RETURNING id",Long.class,tag,tag);
   long customer=db.queryForObject("INSERT INTO customers(customer_no,name,blocked) VALUES(?,?,false) RETURNING id",Long.class,tag,tag);
   long item=db.queryForObject("INSERT INTO items(item_no,description,item_category,item_type,unit_cost,blocked) VALUES(?,?,'INVENTORY','PRODUCTION_COMPONENT',10,false) RETURNING id",Long.class,tag,tag);
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,new BigDecimal("18"),"OPENING",item);
   long order=db.queryForObject("INSERT INTO sales_orders(order_no,customer_id,location_id,order_date,status,currency_code) VALUES(?,?,?,CURRENT_DATE,'OPEN','VND') RETURNING id",Long.class,tag,customer,loc);
   long line=db.queryForObject("INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,description,quantity,quantity_shipped,quantity_invoiced,unit_price,discount_percent,discount_amount,vat_percent,vat_amount,line_amount) VALUES(?,10000,?,'AC',6,0,0,20,0,0,10,12,120) RETURNING id",Long.class,order,item);
   var ship=new SalesShippingController(db);ship.release(order);ship.ship(order,new SalesShippingController.Request(UUID.randomUUID(),null,List.of(new SalesShippingController.Line(line,new BigDecimal("6")))));
   var writer=new ErpWriteController(db);long invoice=Long.parseLong(writer.createSalesInvoiceWithLines(new ErpWriteController.SalesDocumentCreateRequest(Map.of("sales_order_id",Long.toString(order),"currency_code","VND"),List.of(new ErpWriteController.SalesDocumentLineInput(line,new BigDecimal("6"),new BigDecimal("10"))))).get("id").toString());
   db.execute("SET CONSTRAINTS erp_auto_sales_post IMMEDIATE");
   assertEquals(1,db.queryForObject("SELECT count(*) FROM sales_postings WHERE invoice_id=?",Integer.class,invoice));
   assertEquals(0,new BigDecimal("132").compareTo(db.queryForObject("SELECT debit FROM customer_ledger WHERE id=(SELECT customer_entry_id FROM sales_postings WHERE invoice_id=?)",BigDecimal.class,invoice)));
   assertEquals(0,new BigDecimal("60").compareTo(db.queryForObject("SELECT inventory_cost FROM sales_postings WHERE invoice_id=?",BigDecimal.class,invoice)));
   assertEquals(0,db.queryForObject("SELECT sum(debit-credit) FROM general_ledger WHERE id IN (SELECT ledger_id FROM sales_gl_links WHERE invoice_id=?)",BigDecimal.class,invoice).signum());
   assertThrows(Exception.class,()->nested.execute(s->{db.update("UPDATE sales_invoices SET total_amount=1 WHERE id=?",invoice);return null;}));
   assertThrows(Exception.class,()->nested.execute(s->{db.update("DELETE FROM customer_ledger WHERE id=(SELECT customer_entry_id FROM sales_postings WHERE invoice_id=?)",invoice);return null;}));
   org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("accountant","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
   try {
    var accounting=new SalesAccountingController(db);var payment=new SalesAccountingController.Payment(UUID.randomUUID(),java.time.LocalDate.now(),new BigDecimal("100"),"Thu tiền hóa đơn");
    assertEquals(accounting.pay(invoice,payment),accounting.pay(invoice,payment));
    assertEquals(0,new BigDecimal("32").compareTo((BigDecimal)accounting.state(invoice).get("balance")));
    assertThrows(Exception.class,()->accounting.pay(invoice,new SalesAccountingController.Payment(UUID.randomUUID(),null,new BigDecimal("33"),"Thu tiền")));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM payment_allocations WHERE sales_invoice_id=?",Integer.class,invoice));
    assertEquals(0,db.queryForObject("SELECT sum(debit-credit) FROM general_ledger WHERE id IN(SELECT ledger_id FROM sales_gl_links WHERE invoice_id=?)",BigDecimal.class,invoice).signum());
   } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
   long parent=db.queryForObject("INSERT INTO items(item_no,description,item_category,item_type,unit_cost,blocked) VALUES(?,?,'INVENTORY','MANUFACTURED',0,false) RETURNING id",Long.class,tag+"P",tag);
   assertTrue(new ErpDataController(db).productionOrderItems(tag).stream().anyMatch(r->r.get("id").equals(Long.toString(parent))));
   db.update("INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(?,?,4)",parent,item);
   long production=db.queryForObject("INSERT INTO production_orders(production_no,bom_parent_item_id,location_id,quantity) VALUES(?,?,?,2) RETURNING id",Long.class,tag,parent,loc);
   db.update("UPDATE production_orders SET status='COMPLETED' WHERE id=?",production);
   assertEquals(0,new BigDecimal("40").compareTo(db.queryForObject("SELECT average_cost FROM inventory WHERE item_id=? AND location_id=?",BigDecimal.class,parent,loc)));
   assertEquals(0,new BigDecimal("80").compareTo(db.queryForObject("SELECT sum(amount) FROM production_cost_entries WHERE production_order_id=?",BigDecimal.class,production)));
   assertEquals(0,db.queryForObject("SELECT sum(debit-credit) FROM general_ledger WHERE id IN(SELECT ledger_id FROM production_gl_links WHERE production_order_id=?)",BigDecimal.class,production).signum());
   return null;
  });
 }
}
