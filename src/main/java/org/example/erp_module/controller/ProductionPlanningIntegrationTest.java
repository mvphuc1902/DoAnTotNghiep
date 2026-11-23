package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL",matches=".+")
class ProductionPlanningIntegrationTest {
 @Test void bomDemandUsesProductionQuantityAndNetsStockOnceAcrossOrders(){
  var ds=new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD"));
  var db=new JdbcTemplate(ds);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{status.setRollbackOnly(); WarehouseTestSupport.ensureSchema(db);
   String tag="TEST-"+UUID.randomUUID().toString().substring(0,8);
   long vendor=db.queryForObject("INSERT INTO vendors(vendor_no,name) VALUES(?,?) RETURNING id",Long.class,tag,tag);
   long location=db.queryForObject("SELECT id FROM locations ORDER BY id LIMIT 1",Long.class);
   String unit=db.queryForObject("SELECT code FROM units_of_measure WHERE active=true ORDER BY code LIMIT 1",String.class);
   long parent=db.queryForObject("INSERT INTO items(item_no,description,item_category,item_type,base_unit_of_measure,blocked) VALUES(?,?,'INVENTORY','MANUFACTURED',?,false) RETURNING id",Long.class,tag+"-P",tag,unit);
   long component=db.queryForObject("INSERT INTO items(item_no,description,item_category,item_type,base_unit_of_measure,unit_cost,blocked,replenishment_system,preferred_vendor_id) VALUES(?,?,'INVENTORY','PRODUCTION_COMPONENT',?,10,false,'PURCHASE',?) RETURNING id",Long.class,tag+"-C",tag,unit,vendor);
   db.update("INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(?,?,4)",parent,component);
   long first=db.queryForObject("INSERT INTO production_orders(production_no,bom_parent_item_id,location_id,quantity) VALUES(?,?,?,10) RETURNING id",Long.class,tag+"-1",parent,location);
   db.queryForObject("SELECT erp_stock_change(?,?,?,?,?)",Object.class,component,location,new BigDecimal("5"),"TEST",first);
   var planner=new OrderPlanningController(db,new ErpWriteController(db));
   var row=planner.calculate().stream().filter(r->r.get("item_id").equals(Long.toString(component))).findFirst().orElseThrow();
   assertEquals("Production",row.get("demand_type"));assertEquals(0,new BigDecimal("35").compareTo((BigDecimal)row.get("suggested")));
   long second=db.queryForObject("INSERT INTO production_orders(production_no,bom_parent_item_id,location_id,quantity) VALUES(?,?,?,5) RETURNING id",Long.class,tag+"-2",parent,location);
   row=planner.calculate().stream().filter(r->r.get("item_id").equals(Long.toString(component))).findFirst().orElseThrow();
   assertEquals(0,new BigDecimal("55").compareTo((BigDecimal)row.get("suggested")));
   var req=new OrderPlanningController.MakeRequest(UUID.randomUUID(),List.of(new OrderPlanningController.Selection(component,location,new BigDecimal("55"))));
   assertEquals(planner.makeOrders(req),planner.makeOrders(req));
   row=planner.calculate().stream().filter(r->r.get("item_id").equals(Long.toString(component))).findFirst().orElseThrow();
   assertEquals(0,((BigDecimal)row.get("suggested")).signum());
   db.update("UPDATE production_orders SET status='CANCELLED' WHERE id IN (?,?)",first,second);
   assertTrue(planner.calculate().stream().noneMatch(r->r.get("item_id").equals(Long.toString(component))));
   return null;
  });
 }
}
