package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseWorkflowTest {
 @Test void rejectsDuplicateReceiveLinesAndInvalidPrecision() {
  var line=new PurchaseWorkflowController.ReceiveLine(1,new BigDecimal("60"));
  assertThrows(Exception.class,()->PurchaseWorkflowController.validateReceive(new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(line,line))));
  assertThrows(Exception.class,()->PurchaseWorkflowController.validateReceive(new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(new PurchaseWorkflowController.ReceiveLine(1,new BigDecimal("0.0001"))))));
  assertDoesNotThrow(()->PurchaseWorkflowController.validateReceive(new PurchaseWorkflowController.ReceiveRequest(UUID.randomUUID(),null,List.of(line))));
 }
 @Test void refusesNegativeMoneyAndVatAboveCreditAmount() {
  assertThrows(Exception.class,()->PurchaseWorkflowController.validateSettlement(new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,new BigDecimal("-1"),null,"REF",null)));
  assertThrows(Exception.class,()->PurchaseWorkflowController.validateSettlement(new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,BigDecimal.ONE,BigDecimal.TEN,"REF",null)));
  assertDoesNotThrow(()->PurchaseWorkflowController.validateSettlement(new PurchaseWorkflowController.Settlement(UUID.randomUUID(),null,new BigDecimal("3000000"),null,"PAY-1",null)));
 }
}
