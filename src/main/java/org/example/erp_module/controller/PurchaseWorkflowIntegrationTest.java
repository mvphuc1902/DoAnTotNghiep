package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL", matches=".+")
class PurchaseWorkflowIntegrationTest {
 static BigDecimal d(String value) {return new BigDecimal(value);}
 @Test void fullPurchasingPostsStockDebtAndBalancedLedgerWithoutDuplicatingEvents() {
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));
  var db=new JdbcTemplate(ds);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
   status.setRollbackOnly();
   try {
    if(db.queryForObject("SELECT to_regclass('purchase_vendor_entries') IS NULL",Boolean.class)) db.execute(Files.readString(Path.of("src/main/resources/purchase-workflow-v3.sql")));
   }catch(Exception e){throw new RuntimeException(e);}
   var writer=new ErpWriteController(db); var flow=new PurchaseWorkflowController(db,writer);
   String suffix=UUID.randomUUID().toString().substring(0,8);
   long vendor=db.queryForObject("INSERT INTO vendors(vendor_no,name) VALUES(?,?) RETURNING id",Long.class,"TEST-"+suffix,"Rollback vendor");
   String unit=db.queryForObject("SELECT code FROM units_of_measure WHERE active=true ORDER BY code LIMIT 1",String.class);
   String warehouse=db.queryForObject("SELECT code FROM locations ORDER BY id LIMIT 1",String.class);
   long item=db.queryForObject("INSERT INTO items(item_no,description,item_category,base_unit_of_measure,unit_cost,blocked) VALUES(?,?,'INVENTORY',?,50000,false) RETURNING id",Long.class,"TEST-"+suffix,"Rollback item",unit);
   assertEquals(7,db.queryForObject("SELECT count(*) FROM purchase_posting_setup",Integer.class));
   var header=new LinkedHashMap<String,Object>(Map.of("document_type","QUOTE","vendor_id",vendor,"order_date",LocalDate.now().toString(),"currency_code","VND","location_code",warehouse));
   var input=new ErpWriteController.PurchaseDocumentLineInput(null,item,"Rollback item",unit,d("100"),d("50000"),BigDecimal.ZERO,d("10"),warehouse);
   long quote=Long.parseLong(writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(header,List.of(input))).get("id").toString());
   assertEquals(0,db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item).signum());
   long order=Long.parseLong(flow.makeOrder(quote).get("id").toString());
   assertEquals(Long.toString(order),flow.makeOrder(quote).get("id"));
   flow.release(order); assertEquals(0,db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item).signum());
   long line=db.queryForObject("SELECT id FROM purchase_lines WHERE purchase_header_id=?",Long.class,order);
   var first=new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),LocalDate.now(),List.of(new PurchaseWorkflowController.ReceiveLine(line,d("60"))));
   flow.receive(order,first);flow.receive(order,first);
   assertEquals(0,d("60").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   assertThrows(Exception.class,()->flow.receive(order,new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(new PurchaseWorkflowController.ReceiveLine(line,d("41"))))));
   flow.receive(order,new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(new PurchaseWorkflowController.ReceiveLine(line,d("40")))));
   var invoiceLines=new ArrayList<ErpWriteController.PurchaseDocumentLineInput>();
   for(var receipt:db.queryForList("SELECT * FROM purchase_receipt_lines WHERE purchase_order_line_id=? ORDER BY id",line)) invoiceLines.add(new ErpWriteController.PurchaseDocumentLineInput(((Number)receipt.get("id")).longValue(),null,null,null,(BigDecimal)receipt.get("quantity"),d("50000"),BigDecimal.ZERO,d("10"),warehouse));
   header.put("document_type","INVOICE");header.put("external_document_no","TEST-INV-"+suffix);
   long invoice=Long.parseLong(writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(header,invoiceLines)).get("id").toString());
   assertEquals(0,db.queryForObject("SELECT quantity_invoiced FROM purchase_lines WHERE id=?",BigDecimal.class,line).signum());
   assertEquals(0,db.queryForObject("SELECT count(*) FROM purchase_vendor_entries WHERE invoice_id=?",Integer.class,invoice));
   flow.invoice(invoice,new PurchaseWorkflowController.Posting(LocalDate.now()));flow.invoice(invoice,new PurchaseWorkflowController.Posting(LocalDate.now()));
   assertEquals(0,d("5500000").compareTo((BigDecimal)flow.history(invoice).get("balance")));
   assertEquals(0,d("100").compareTo(db.queryForObject("SELECT quantity_invoiced FROM purchase_lines WHERE id=?",BigDecimal.class,line)));
   var payment=new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,d("3000000"),null,"PAY-1",null);
   flow.pay(invoice,payment);flow.pay(invoice,payment);
   assertEquals(0,d("2500000").compareTo((BigDecimal)flow.history(invoice).get("balance")));
   assertThrows(Exception.class,()->flow.pay(invoice,new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,d("2500001"),null,"OVERPAY",null)));
   header.put("document_type","RETURN_ORDER");
   var retLine=new ErpWriteController.PurchaseDocumentLineInput(null,item,"Return",unit,d("10"),d("50000"),BigDecimal.ZERO,d("10"),warehouse);
   long ret=Long.parseLong(writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(header,List.of(retLine))).get("id").toString());
   flow.release(ret);flow.shipReturn(ret,new PurchaseWorkflowController.Posting(null));flow.shipReturn(ret,new PurchaseWorkflowController.Posting(null));
   assertEquals(0,d("90").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   assertEquals(0,d("2500000").compareTo((BigDecimal)flow.history(invoice).get("balance")));
   flow.credit(invoice,new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,d("550000"),d("50000"),"CREDIT-1",ret));
   flow.pay(invoice,new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,d("1950000"),null,"PAY-2",null));
   assertEquals(0,((BigDecimal)flow.history(invoice).get("balance")).signum());
   flow.credit(invoice,new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,d("110000"),d("10000"),"PRICE-REDUCTION",null));
   assertEquals(0,d("-110000").compareTo((BigDecimal)flow.history(invoice).get("balance")));
   var refund=new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,d("110000"),null,"REFUND",null);
   flow.refund(invoice,refund);flow.refund(invoice,refund);
   assertEquals(0,((BigDecimal)flow.history(invoice).get("balance")).signum());
   assertEquals(0,db.queryForObject("SELECT coalesce(sum(g.credit-g.debit),0) FROM general_ledger g JOIN purchase_gl_links k ON k.ledger_id=g.id JOIN purchase_posting_setup p ON p.account_id=g.account_id AND p.role='PAYABLE' WHERE k.purchase_header_id=?",BigDecimal.class,invoice).signum());
   assertEquals(0,db.queryForObject("SELECT count(*) FROM (SELECT document_no FROM general_ledger WHERE id IN(SELECT ledger_id FROM purchase_gl_links WHERE purchase_header_id IN (?,?)) GROUP BY document_no HAVING sum(debit)<>sum(credit)) unbalanced",Integer.class,invoice,ret));
   assertEquals(0,d("90").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   header.put("document_type","ORDER");
   long combinedOrder=Long.parseLong(writer.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(header,List.of(retLine))).get("id").toString());
   flow.release(combinedOrder);
   long combinedLine=db.queryForObject("SELECT id FROM purchase_lines WHERE purchase_header_id=?",Long.class,combinedOrder);
   var combined=new PurchaseWorkflowController.ReceiveInvoiceRequest(new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(new PurchaseWorkflowController.ReceiveLine(combinedLine,d("10")))),"COMBINED-"+suffix);
   long combinedInvoice=Long.parseLong(flow.receiveInvoice(combinedOrder,combined).get("id").toString());
   assertEquals(Long.toString(combinedInvoice),flow.receiveInvoice(combinedOrder,combined).get("id"));
   assertEquals(0,d("550000").compareTo((BigDecimal)flow.history(combinedInvoice).get("balance")));
   assertEquals(0,d("100").compareTo(db.queryForObject("SELECT quantity FROM items WHERE id=?",BigDecimal.class,item)));
   return null;
  });
 }
}
