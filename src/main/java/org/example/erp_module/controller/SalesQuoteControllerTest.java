package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.example.customer_module.entity.Customer;
import org.example.items_module.entity.Items;
import org.example.salesorder_module.entity.SalesOrder;
import org.example.salesorder_module.entity.SalesOrderLine;
import org.example.salesorder_module.service.impl.SalesOrderServiceImpl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

class SalesQuoteControllerTest {
    @Test void retryMakeInvoiceReturnsExistingInvoiceWithoutCreatingAnotherOrder() {
        JdbcTemplate database=mock(JdbcTemplate.class);
        SalesOrderServiceImpl service=mock(SalesOrderServiceImpl.class);
        when(database.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE",7L)).thenReturn(List.of(Map.of("document_type","QUOTE")));
        when(database.queryForList("SELECT sales_invoice_id FROM sales_quote_invoice_links WHERE sales_header_id=?",Long.class,7L)).thenReturn(List.of(19L));
        assertEquals("19",new SalesQuoteController(database,service).makeInvoice(7L).get("id"));
        verifyNoInteractions(service);
        verify(database,never()).update(anyString(),any(Object[].class));
    }

    @Test void makeInvoiceRejectsNonQuoteBeforeWriting() {
        JdbcTemplate database=mock(JdbcTemplate.class);
        SalesOrderServiceImpl service=mock(SalesOrderServiceImpl.class);
        when(database.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE",7L)).thenReturn(List.of(Map.of("document_type","RETURN_ORDER")));
        assertThrows(ResponseStatusException.class,()->new SalesQuoteController(database,service).makeInvoice(7L));
        verifyNoInteractions(service);
    }
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SalesOrderServiceImpl orders = mock(SalesOrderServiceImpl.class);
    private final SalesQuoteController controller = new SalesQuoteController(jdbc, orders);

    @Test void shortageIdentifiesItemAndWarehouseWithoutCreatingOrder() {
        when(jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE", 7L))
            .thenReturn(List.of(Map.of("document_type", "QUOTE", "status", "DRAFT", "location_id", 3L)));
        when(jdbc.queryForList(contains("AS requested_quantity"), eq(7L), eq(3L)))
            .thenReturn(List.of(Map.of("item_no", "ITEM-01", "warehouse_code", "MAIN",
                    "requested_quantity", new BigDecimal("10"), "available_quantity", new BigDecimal("2"))));
        var error = assertThrows(ResponseStatusException.class, () -> controller.makeOrder(7));
        assertTrue(error.getReason().contains("ITEM-01 tại kho MAIN: cần 10, còn 2"));
        verifyNoInteractions(orders);
    }

    @Test void shortageMessageDoesNotShowTrailingDecimalZerosForWholeQuantities() {
        when(jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE", 7L))
            .thenReturn(List.of(Map.of("document_type", "QUOTE", "status", "DRAFT", "location_id", 3L)));
        when(jdbc.queryForList(contains("AS requested_quantity"), eq(7L), eq(3L)))
            .thenReturn(List.of(Map.of("item_no", "ITEM-01", "warehouse_code", "MAIN",
                    "requested_quantity", new BigDecimal("1.000"), "available_quantity", new BigDecimal("0.000"))));

        var error = assertThrows(ResponseStatusException.class, () -> controller.makeOrder(7));

        assertTrue(error.getReason().contains("ITEM-01 tại kho MAIN: cần 1, còn 0"));
        assertFalse(error.getReason().contains("1.000"));
        assertFalse(error.getReason().contains("0.000"));
        verifyNoInteractions(orders);
    }

    @Test void databaseStockErrorIsNotHiddenByGenericMessage() {
        var error = new org.springframework.dao.DataIntegrityViolationException("insert failed",
                new java.sql.SQLException("ERROR: STOCK: Không đủ tồn kho khả dụng.\nWhere: trigger", "23514"));
        String message = controller.constraint(error).getBody().get("message");
        assertTrue(message.contains("Không đủ tồn kho khả dụng."));
        assertFalse(message.contains("Where:"));
    }

    @Test void conversionGeneratesDocumentNumberAndUsesCurrentPostingDate() {
        when(jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE", 7L))
            .thenReturn(List.of(Map.of("document_type", "QUOTE", "status", "DRAFT", "customer_id", 1L,
                    "order_date", java.sql.Date.valueOf("2020-01-01"), "currency_code", "VND")));
        when(jdbc.queryForList("SELECT * FROM sales_quote_lines WHERE sales_header_id=? ORDER BY line_no", 7L))
            .thenReturn(List.of(Map.of("item_id", 2L, "line_no", 10000, "quantity", BigDecimal.ONE,
                    "unit_price", BigDecimal.TEN, "discount_percent", BigDecimal.ZERO, "vat_percent", BigDecimal.ZERO)));
        when(orders.create(any())).thenAnswer(invocation -> {
            SalesOrder order = invocation.getArgument(0);
            order.setOrderNo("SO-001");
            assertEquals(java.time.LocalDate.now(), order.getPostingDate());
            assertEquals("SO-001", order.getOrderNo());
            assertEquals(BigDecimal.TEN, order.getLines().getFirst().getUnitPrice());
            order.setId(42L); return order;
        });
        var result = controller.makeOrder(7);
        assertEquals("42", result.get("id"));
        assertEquals("SO-001", result.get("documentNo"));
        assertEquals(java.time.LocalDate.now().toString(), result.get("postingDate"));
    }

    @Test void retryReturnsExistingOrderWithoutCreatingAnother() {
        when(jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE", 7L))
            .thenReturn(List.of(Map.of("document_type", "QUOTE", "sales_order_id", 42L, "status", "CONVERTED")));
        assertEquals("42", controller.makeOrder(7).get("id"));
        verifyNoInteractions(orders);
    }

    @Test void makeOrderRejectsNonQuoteDocumentsEvenIfTheyAlreadyReferenceAnOrder() {
        for (String documentType : List.of("ORDER", "INVOICE", "RETURN_ORDER")) {
            when(jdbc.queryForList("SELECT * FROM sales_headers WHERE id=? FOR UPDATE", 7L))
                .thenReturn(List.of(Map.of("document_type", documentType, "sales_order_id", 42L, "status", "CONVERTED")));

            var error = assertThrows(ResponseStatusException.class, () -> controller.makeOrder(7));

            assertTrue(error.getReason().contains("Chỉ Sales Quotes"));
        }
        verifyNoInteractions(orders);
    }

    @Test void emptyQuoteIsRejectedBeforeWriting() {
        assertThrows(ResponseStatusException.class, () -> controller.create(new SalesOrder()));
        verifyNoInteractions(jdbc, orders);
    }

    @Test void savingQuoteOnlyPreparesDocumentAndDoesNotSaveAnOrder() {
        SalesOrder quote = new SalesOrder();
        Customer customer = new Customer(); customer.setId(1L); quote.setCustomer(customer);
        SalesOrderLine line = new SalesOrderLine(); Items item = new Items(); item.setId(2L); line.setItem(item);
        line.setLineNo(10000); line.setQuantity(BigDecimal.ONE); line.setUnitPrice(BigDecimal.TEN);
        line.setDiscountPercent(BigDecimal.ZERO); line.setVatPercent(BigDecimal.ZERO);
        quote.setLines(List.of(line));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        when(jdbc.queryForObject("SELECT erp_next_document_no(?)", String.class, "SQ")).thenReturn("SQ-001");
        assertEquals("7", controller.create(quote).get("id"));
        assertEquals("SQ-001", quote.getOrderNo());
        verify(orders).prepare(quote);
        verify(orders, never()).create(any());
    }

    @Test void creatingSalesOrderAlsoCreatesTheSalesHeaderAndItsLines() {
        SalesOrder order = new SalesOrder();
        order.setDocumentType("ORDER");
        order.setOrderNo("SO-000001");
        Customer customer = new Customer(); customer.setId(1L); order.setCustomer(customer);
        SalesOrderLine line = new SalesOrderLine(); Items item = new Items(); item.setId(2L);
        line.setItem(item); line.setLineNo(10000); line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.TEN); line.setDiscountPercent(BigDecimal.ZERO); line.setVatPercent(BigDecimal.ZERO);
        order.setLines(List.of(line));
        when(orders.create(order)).thenAnswer(invocation -> {
            SalesOrder saved = invocation.getArgument(0); saved.setId(42L); return saved;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);

        var result = controller.create(order);

        assertEquals("7", result.get("id"));
        assertEquals("SO-000001", result.get("documentNo"));
        verify(orders).create(order);
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("INSERT INTO sales_headers"), eq(Long.class), values.capture());
        assertEquals("ORDER", values.getValue()[0]);
        assertEquals("SO-000001", values.getValue()[1]);
        assertEquals("OPEN", values.getValue()[10]);
        assertEquals(42L, values.getValue()[16]);
        verify(jdbc).update(contains("INSERT INTO sales_quote_lines"), eq(7L), eq(10000), eq(2L),
                nullable(String.class), nullable(String.class), eq(BigDecimal.ONE), eq(BigDecimal.TEN),
                eq(BigDecimal.ZERO), eq(BigDecimal.ZERO));
    }

    @Test void salesHeaderWithAnOrderReadsLinesFromItsSalesOrder() {
        when(jdbc.queryForList("SELECT sales_order_id FROM sales_headers WHERE id=?", 7L))
                .thenReturn(List.of(Map.of("sales_order_id", 42L)));
        when(jdbc.queryForList(contains("FROM sales_lines l LEFT JOIN items i"), eq(7L)))
                .thenReturn(List.of(Map.of("line_no", 10000, "item_no", "ITEM-1")));

        var lines = controller.lines(7L);

        assertEquals(1, lines.size());
        assertEquals("ITEM-1", lines.getFirst().get("item_no"));
    }

    @Test void salesHeaderWithReturnTypeReadsReturnLines() {
        when(jdbc.queryForList("SELECT sales_order_id FROM sales_headers WHERE id=?", 8L))
                .thenReturn(List.of(Map.of("sales_order_id", 42L)));
        when(jdbc.queryForObject("SELECT document_type FROM sales_headers WHERE id=?", String.class, 8L))
                .thenReturn("RETURN_ORDER");
        when(jdbc.queryForObject("SELECT sales_return_id FROM sales_headers WHERE id=?", Long.class, 8L))
                .thenReturn(12L);
        when(jdbc.queryForList(contains("FROM sales_lines l LEFT JOIN items i"), eq(8L)))
                .thenReturn(List.of(Map.of("item_no", "ITEM-1", "quantity", "1.000")));

        assertEquals("ITEM-1", controller.lines(8L).getFirst().get("item_no"));
    }

    @Test void salesHeaderWithInvoiceTypeReadsInvoiceLines() {
        when(jdbc.queryForList("SELECT sales_order_id FROM sales_headers WHERE id=?", 9L))
                .thenReturn(List.of(Map.of("sales_order_id", 42L)));
        when(jdbc.queryForObject("SELECT document_type FROM sales_headers WHERE id=?", String.class, 9L))
                .thenReturn("INVOICE");
        when(jdbc.queryForObject("SELECT sales_invoice_id FROM sales_headers WHERE id=?", Long.class, 9L))
                .thenReturn(15L);
        when(jdbc.queryForList(contains("FROM sales_lines l LEFT JOIN items i"), eq(9L)))
                .thenReturn(List.of(Map.of("item_no", "ITEM-2", "quantity", "2.000")));

        assertEquals("ITEM-2", controller.lines(9L).getFirst().get("item_no"));
    }
}
