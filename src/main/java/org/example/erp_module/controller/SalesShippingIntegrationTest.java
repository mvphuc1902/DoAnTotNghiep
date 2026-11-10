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
class SalesShippingIntegrationTest {
 @Test void ordersDoNotConsumeStockAndShipmentsInvoiceAndReturnsRespectDeliveredQuantity(){
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));
  var manager=new DataSourceTransactionManager(ds);var db=new JdbcTemplate(ds);var tx=new TransactionTemplate(manager);
  var nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  tx.execute(status->{status.setRollbackOnly();try{if(db.queryForObject("SELECT to_regclass('sales_shipments') IS NULL",Boolean.class))db.execute(Files.readString(Path.of("src/main/resources/sales-shipping-v8.sql")));}catch(Exception e){throw new RuntimeException(e);}
   String suffix=UUID.randomUUID().toString().substring(0,8);
   long customer=db.queryForObject("INSERT INTO customers(customer_no,name,blocked) VALUES(?,?,false) RETURNING id",Long.class,"TEST-"+suffix,"Rollback customer");
   long location=db.queryForObject("SELECT id FROM locations ORDER BY id LIMIT 1",Long.class);
   long item=db.queryForObject("INSERT INTO items(item_no,description,item_category,blocked,unit_cost) VALUES(?,?,'INVENTORY',false,10) RETURNING id",Long.class,"TEST-"+suffix,"Rollback item");
   long order=db.queryForObject("INSERT INTO sales_orders(order_no,customer_id,location_id,order_date,status,currency_code) VALUES(?,?,?,CURRENT_DATE,'OPEN','VND') RETURNING id",Long.class,"SO-TEST-"+suffix,customer,location);
   long line=db.queryForObject("INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,description,quantity,quantity_shipped,quantity_invoiced,unit_price,discount_percent,discount_amount,vat_percent,vat_amount,line_amount) VALUES(?,10000,?,'Rollback',10,0,0,10,0,0,0,0,100) RETURNING id",Long.class,order,item);
   assertEquals(0,db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item).signum());
   var controller=new SalesShippingController(db);controller.release(order);controller.release(order);
   assertEquals(0,db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item).signum());
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,location,new BigDecimal("8"),"TEST",order);
   var request=new SalesShippingController.Request(UUID.randomUUID(),null,List.of(new SalesShippingController.Line(line,new BigDecimal("6"))));
   assertEquals(controller.ship(order,request).get("id"),controller.ship(order,request).get("id"));
   assertEquals(0,new BigDecimal("2").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   assertThrows(Exception.class,()->controller.ship(order,new SalesShippingController.Request(UUID.randomUUID(),null,List.of(new SalesShippingController.Line(line,new BigDecimal("5"))))));
   assertThrows(Exception.class,()->nested.execute(s->{controller.ship(order,new SalesShippingController.Request(UUID.randomUUID(),null,List.of(new SalesShippingController.Line(line,new BigDecimal("3")))));return null;}));
   assertEquals(1,db.queryForObject("SELECT count(*) FROM sales_shipments WHERE sales_order_id=?",Integer.class,order));
   long invoice=db.queryForObject("INSERT INTO sales_invoices(invoice_no,sales_order_id,customer_id,invoice_date,posting_date,currency_code) VALUES(?,?,?,CURRENT_DATE,CURRENT_DATE,'VND') RETURNING id",Long.class,"S-INV-TEST-"+suffix,order,customer);
   assertThrows(Exception.class,()->nested.execute(s->{db.update("INSERT INTO sales_invoice_lines(invoice_id,line_no,sales_order_line_id,item_id,quantity,unit_price) VALUES(?,10000,?,?,7,10)",invoice,line,item);return null;}));
   db.update("INSERT INTO sales_invoice_lines(invoice_id,line_no,sales_order_line_id,item_id,quantity,unit_price) VALUES(?,10000,?,?,6,10)",invoice,line,item);
   assertEquals(0,new BigDecimal("2").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   long ret=db.queryForObject("INSERT INTO sales_returns(return_no,sales_order_id,location_id) VALUES(?,?,?) RETURNING id",Long.class,"SRO-TEST-"+suffix,order,location);
   db.update("INSERT INTO sales_return_lines(return_id,sales_order_line_id,item_id,quantity) VALUES(?,?,?,1)",ret,line,item);
   db.update("UPDATE sales_returns SET status='RECEIVED' WHERE id=?",ret);
   assertEquals(0,new BigDecimal("3").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   controller.ship(order,new SalesShippingController.Request(UUID.randomUUID(),null,List.of(new SalesShippingController.Line(line,BigDecimal.ONE))));
   assertEquals(0,new BigDecimal("2").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   assertThrows(Exception.class,()->nested.execute(s->{db.update("UPDATE sales_order_lines SET quantity=12 WHERE id=?",line);return null;}));
   return null;
  });
 }
}
