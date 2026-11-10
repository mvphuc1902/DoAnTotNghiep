package org.example.erp_module.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesFormControllerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SalesFormController controller=new SalesFormController(jdbc);

    @Test
    void deletesOnlyOrphanedInvoiceAndItsStaleLinks() {
        when(jdbc.queryForList(contains("document_type='INVOICE'"),eq(5L)))
                .thenReturn(List.of(Map.of("sales_invoice_id",1L)));
        when(jdbc.queryForObject(contains("FROM sales_invoices"),eq(Boolean.class),eq(1L))).thenReturn(false);
        when(jdbc.queryForList(contains("FROM pg_constraint"))).thenReturn(List.of());
        when(jdbc.update("DELETE FROM sales_headers WHERE id=? AND document_type='INVOICE'",5L)).thenReturn(1);

        controller.deleteOrphanedInvoice(5L);

        var ordered=inOrder(jdbc);
        ordered.verify(jdbc).update("DELETE FROM sales_quote_invoice_links WHERE sales_invoice_id=?",1L);
        ordered.verify(jdbc).update("DELETE FROM sales_document_archives WHERE sales_header_id=?",5L);
        ordered.verify(jdbc).update("DELETE FROM sales_headers WHERE id=? AND document_type='INVOICE'",5L);
    }

    @Test
    void refusesToDeleteInvoiceWhenItsSourceStillExists() {
        when(jdbc.queryForList(contains("document_type='INVOICE'"),eq(5L)))
                .thenReturn(List.of(Map.of("sales_invoice_id",1L)));
        when(jdbc.queryForObject(contains("FROM sales_invoices"),eq(Boolean.class),eq(1L))).thenReturn(true);

        var error=assertThrows(ResponseStatusException.class,()->controller.deleteOrphanedInvoice(5L));

        assertEquals(409,error.getStatusCode().value());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void itemLookupUsesItemsButOnlyReturnsLinesFromSelectedOrder() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(42L))).thenReturn(true);
        when(jdbc.queryForList(anyString(),any(Object[].class)))
                .thenReturn(List.of(Map.of("id",9L,"item_id",3L,"sales_order_id",42L,
                        "item_no","ITM-1","line_no",10000)));

        var result=controller.orderItems(42L,"bolt","INVENTORY");

        assertEquals(1,result.size());
        assertEquals("ITM-1",result.getFirst().get("item_no"));
        assertEquals("9",result.getFirst().get("id"));
        assertEquals("3",result.getFirst().get("item_id"));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertTrue(sql.getValue().contains("JOIN items item ON item.id=line.item_id"));
        assertTrue(sql.getValue().contains("WHERE line.sales_order_id=?"));
        assertTrue(sql.getValue().contains("item.item_category=?"));
        assertEquals(List.of(42L,"INVENTORY","bolt","bolt","bolt"),Arrays.asList(args.getValue()));
    }

    @Test
    void itemLookupRejectsInvalidCategory() {
        var error=assertThrows(ResponseStatusException.class,()->controller.orderItems(42L,"","UNKNOWN"));
        assertEquals(400,error.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
}
