package org.example.erp_module.controller;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL",matches=".+")
class WarehouseVendorIntegrationTest {
 @Test void warehouseReferencesStayValidAndVendorBalancesSeparateCurrencies(){
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));var db=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);
  var tx=new TransactionTemplate(manager);var nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  tx.execute(status->{status.setRollbackOnly(); WarehouseTestSupport.ensureSchema(db);try{db.execute(Files.readString(Path.of("src/main/resources/warehouse-vendor-v1.sql")));}catch(Exception e){throw new RuntimeException(e);}
   String code="WV-"+UUID.randomUUID().toString().substring(0,8);
   long loc=db.queryForObject("INSERT INTO locations(code,name) VALUES(?,?) RETURNING id",Long.class,code,code);
   long vendor=db.queryForObject("INSERT INTO vendors(vendor_no,name) VALUES(?,?) RETURNING id",Long.class,code,code);
   long item=db.queryForObject("INSERT INTO items(item_no,description,item_category,blocked) VALUES(?,?,'INVENTORY',false) RETURNING id",Long.class,code,code);
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,item,loc,new BigDecimal("30"),"TEST",loc);
   long order=db.queryForObject("INSERT INTO purchase_headers(document_type,document_no,vendor_id,location_code) VALUES('ORDER',?,?,?) RETURNING id",Long.class,code,vendor,code);
   db.update("INSERT INTO purchase_lines(purchase_header_id,document_type,document_no,line_no,item_id,description,location_code,quantity,unit_price) VALUES(?,'ORDER',?,10000,?,?,?,70,1)",order,code,item,code,code);
   var api=new WarehouseVendorController(db);var stock=(List<Map<String,Object>>)api.warehouse(loc).get("stock");
   assertEquals(1,stock.size());assertEquals(0,new BigDecimal("30").compareTo((BigDecimal)stock.getFirst().get("available")));assertEquals(0,new BigDecimal("70").compareTo((BigDecimal)stock.getFirst().get("incoming")));
   assertThrows(Exception.class,()->nested.execute(s->{db.update("UPDATE locations SET code=? WHERE id=?",code+"X",loc);return null;}));
   assertThrows(Exception.class,()->nested.execute(s->{db.update("INSERT INTO purchase_headers(document_type,document_no,vendor_id,location_code) VALUES('QUOTE',?,?,?)",code+"BAD",vendor,code+"BAD");return null;}));
   for(String currency:List.of("VND","USD")){
    long invoice=db.queryForObject("INSERT INTO purchase_headers(document_type,document_no,vendor_id,currency_code) VALUES('INVOICE',?,?,?) RETURNING id",Long.class,code+currency,vendor,currency);
    db.update("INSERT INTO purchase_vendor_entries(invoice_id,vendor_id,entry_type,document_no,posting_date,currency_code,amount,reference) VALUES(?,?,'INVOICE',?,CURRENT_DATE,?,100,'TEST')",invoice,vendor,code+currency,currency);
   }
   var balances=(List<Map<String,Object>>)api.vendor(vendor).get("balances");assertEquals(2,balances.size());for(var balance:balances)assertEquals(0,new BigDecimal("100").compareTo((BigDecimal)balance.get("balance")));
   assertThrows(Exception.class,()->new ErpWriteController(db).delete("purchase_vendor_entries",1));
   return null;
  });
 }
}
