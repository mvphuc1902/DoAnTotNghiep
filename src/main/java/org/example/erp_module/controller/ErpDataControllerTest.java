package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ErpDataControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ErpDataController controller = new ErpDataController(jdbc);

    @Test
    void rejectsUnknownTablesAndHiddenColumnsBeforeQuerying() {
        assertThrows(ResponseStatusException.class, () -> controller.rows("pg_authid", 0, 25, "", null, null));
        assertThrows(ResponseStatusException.class, () -> controller.rows("users", 0, 25, "", "password", "secret"));
        assertFalse(ErpTables.COLUMNS.get("users").contains("password"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void validatesPagination() {
        assertThrows(ResponseStatusException.class, () -> controller.rows("items", -1, 25, "", null, null));
        assertThrows(ResponseStatusException.class, () -> controller.rows("items", 0, 101, "", null, null));
        verifyNoInteractions(jdbc);
    }
    @Test
    void productsAndCategoriesAreNotExposedInCatalogOrCrud() {
        for(String table:List.of("product","category")) {
        assertFalse(ErpTables.COLUMNS.containsKey(table));
        assertThrows(ResponseStatusException.class,()->controller.rows(table,0,25,"",null,null));
        var writer=new ErpWriteController(jdbc);
        assertThrows(ResponseStatusException.class,()->writer.schema(table));
        assertThrows(ResponseStatusException.class,()->writer.create(table,Map.of()));
        assertThrows(ResponseStatusException.class,()->writer.update(table,1,Map.of()));
        assertThrows(ResponseStatusException.class,()->writer.delete(table,1));
        }
        verifyNoInteractions(jdbc);
    }
    @Test
    void inventoryIncludesNamesAndCanSearchByMaterialName() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(new ArrayList<>());
        var result=controller.rows("inventory",0,25,"Laptop",null,null);
        assertTrue(((List<?>)result.get("columns")).containsAll(List.of("item_code","item_name","location_name","unit")));
        verify(jdbc).queryForList(argThat(sql->sql.contains("LEFT JOIN items item ON item.id=stock.item_id")
            &&sql.contains("AS item_name")&&sql.contains("CAST(\"item_name\" AS text)")&&!sql.contains("Laptop")),any(Object[].class));
        assertFalse(ErpTables.COLUMNS.get("inventory").contains("item_name"));
    }

    @Test
    void preservesExactAmountsAndLargeIdentifiers() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9007199254740993L);
        row.put("unit_price", new BigDecimal("123456789012345678.90"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>(List.of(row)));
        Map<String, Object> result = controller.rows("items", 0, 25, "", null, null);
        assertEquals(1L, result.get("total"));
        assertEquals("9007199254740993", row.get("id"));
        assertEquals("123456789012345678.90", row.get("unit_price"));
    }

    @Test
    void bindsSearchAndRelationFilterAsParameters() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());
        String search = "x' OR 1=1 --";
        controller.rows("sales_orders", 2, 25, search, "customer_id", "5");
        org.mockito.ArgumentCaptor<Object[]> parameters = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(argThat(sql -> !sql.contains(search)
            && sql.contains("CAST(\"customer_id\" AS text) = ?") && sql.endsWith("LIMIT ? OFFSET ?")),
            parameters.capture());
        assertTrue(Arrays.asList(parameters.getValue()).contains(search));
        assertEquals(50L, parameters.getValue()[parameters.getValue().length - 1]);
    }

    @Test
    void salesDocumentLinesUnifiesAllFourDocumentTypesWithSearchAndPaging() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(4L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());

        Map<String, Object> result = controller.rows("sales_document_lines", 1, 25, "ITEM001", null, null);

        assertEquals(4L, result.get("total"));
        assertTrue(((List<?>) result.get("columns")).containsAll(List.of(
                "document_type", "document_no", "customer_no", "item_no", "quantity", "unit_price", "line_amount")));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> parameters = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), parameters.capture());
        assertTrue(sql.getValue().contains("FROM sales_headers header"));
        assertTrue(sql.getValue().contains("LEFT JOIN sales_lines line ON line.sales_header_id=header.id"));
        assertTrue(sql.getValue().contains("customer.customer_no AS customer_no"));
        assertFalse(sql.getValue().contains("UNION ALL"));
        assertTrue(sql.getValue().contains("AS line_status"));
        assertTrue(sql.getValue().endsWith("ORDER BY document_date DESC, document_no, line_no LIMIT ? OFFSET ?"));
        assertTrue(Arrays.asList(parameters.getValue()).contains("ITEM001"));
        assertEquals(25, parameters.getValue()[parameters.getValue().length - 2]);
        assertEquals(25L, parameters.getValue()[parameters.getValue().length - 1]);
    }

    @Test
    void validatesSalesDocumentLineFiltersBeforeQuerying() {
        assertThrows(ResponseStatusException.class,
                () -> controller.rows("sales_document_lines", 0, 25, "", "not_a_column", "value"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void salesOrderLookupSelectsOnlyColumnsPresentInTheSalesOrderSchema() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());

        controller.rows("sales_orders", 0, 100, "", null, null);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("\"order_no\""));
        assertFalse(sql.getValue().contains("\"created_at\""));
        assertFalse(sql.getValue().contains("\"updated_at\""));
    }

    @Test
    void salesHeadersIncludeCustomerCodeAndLocationCodeInsteadOfDisplayingIds() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());

        Map<String, Object> result = controller.rows("sales_headers", 0, 25, "", null, null);

        assertTrue(((List<?>) result.get("columns")).containsAll(List.of(
                "customer_id", "location_id", "customer_no", "location_code", "sell_to_name",
                "external_document_no", "sell_to_contact", "due_date", "quote_valid_until_date",
                "assigned_user_id", "document_date")));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("customer.customer_no AS customer_no"));
        assertTrue(sql.getValue().contains("location.code AS location_code"));
        assertTrue(sql.getValue().contains("LEFT JOIN public.customers customer ON customer.id=header.customer_id"));
        assertTrue(sql.getValue().contains("LEFT JOIN public.locations location ON location.id=header.location_id"));
    }
}
