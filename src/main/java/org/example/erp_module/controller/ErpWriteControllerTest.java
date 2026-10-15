package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ErpWriteControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ErpWriteController controller = spy(new ErpWriteController(jdbc));


    @Test
    void inventoryDeleteRequiresAdminAndAdjustsStockBeforeDelete() {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var previous = context.getAuthentication();
        try {
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "director", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DIRECTOR"))));
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.delete("inventory", 8L)).getStatusCode().value());
            verifyNoInteractions(jdbc);
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
            when(jdbc.queryForList(contains("FOR UPDATE"), eq(8L))).thenReturn(List.of(Map.of("reserved_quantity", BigDecimal.ONE)));
            assertThrows(ResponseStatusException.class, () -> controller.delete("inventory", 8L));
            verify(jdbc, never()).update(anyString(), any(Object[].class));
            when(jdbc.queryForList(contains("FOR UPDATE"), eq(8L))).thenReturn(List.of(Map.of("reserved_quantity", BigDecimal.ZERO)));
            controller.delete("inventory", 8L);
            var ordered = inOrder(jdbc);

            ordered.verify(jdbc).execute("SELECT set_config('erp.admin_inventory_delete','on',true)");
            ordered.verify(jdbc).update("UPDATE inventory SET quantity=0 WHERE id=?", 8L);
            ordered.verify(jdbc).update("DELETE FROM inventory WHERE id=?", 8L);
            clearInvocations(jdbc);
            when(jdbc.queryForList(contains("FOR UPDATE"), eq(8L))).thenReturn(List.of(Map.of("reserved_quantity", BigDecimal.ONE)));
            controller.delete("inventory", 8L, true);
            var releaseOrder = inOrder(jdbc);
            releaseOrder.verify(jdbc).update(contains("INSERT INTO inventory_ledger"), any(Object[].class));
            releaseOrder.verify(jdbc).update("UPDATE inventory SET reserved_quantity=0 WHERE id=?", 8L);
            releaseOrder.verify(jdbc).update("UPDATE inventory SET quantity=0 WHERE id=?", 8L);
            releaseOrder.verify(jdbc).update("DELETE FROM inventory WHERE id=?", 8L);
        } finally { context.setAuthentication(previous); }
    }

    @Test
    void purchaseDeleteRemovesOwnLinesBeforeHeader() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(7L))).thenReturn(List.of(Map.of("status", "DRAFT")));
        controller.delete("purchase_headers", 7L);
        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).update("DELETE FROM purchase_lines WHERE purchase_header_id=?", 7L);
        ordered.verify(jdbc).update("DELETE FROM purchase_headers WHERE id=?", 7L);
    }

    @Test
    void purchaseDeleteRejectsPostedAndReferencedDocuments() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(7L))).thenReturn(List.of(Map.of("status", "POSTED")));
        assertThrows(ResponseStatusException.class, () -> controller.delete("purchase_headers", 7L));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(7L))).thenReturn(List.of(Map.of("status", "DRAFT")));
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM purchase_receipts"), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> controller.delete("purchase_headers", 7L));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void purchaseEditAllowsNotesButRejectsChangingDocumentType() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(7L))).thenReturn(List.of(Map.of("status", "DRAFT")));
        doReturn(List.of(field("notes", "text", false, false))).when(controller).schema("purchase_headers");
        assertEquals("7", controller.update("purchase_headers", 7L, Map.of("notes", "Updated")).get("id"));
        verify(jdbc).update(contains("UPDATE purchase_headers"), eq("Updated"), eq(7L));
        assertThrows(ResponseStatusException.class, () -> controller.update("purchase_headers", 7L, Map.of("document_type", "INVOICE")));
    }

    @Test
    void purchaseLinesKeepSelectedWarehouseAndDefaultToHeaderWarehouse() {
        for (String type : List.of("ORDER", "QUOTE")) {
            for (String selected : new String[]{"KHO-HN", null}) {
                reset(jdbc);
                when(jdbc.queryForObject(contains("FROM units_of_measure"), eq(Boolean.class), eq("EA"))).thenReturn(true);
        when(jdbc.queryForObject(contains("FROM vendors"), eq(Boolean.class), eq(4L))).thenReturn(true);
                when(jdbc.queryForObject(contains("FROM locations"), eq(Boolean.class), anyString())).thenReturn(true);
                when(jdbc.queryForObject(eq("SELECT erp_next_document_no(?)"), eq(String.class), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(2).equals("PO") ? "PO-001" : "PQ-001");
                when(jdbc.queryForObject(contains("INSERT INTO purchase_headers"), eq(Long.class), any(Object[].class))).thenReturn(7L);
                when(jdbc.queryForList(contains("SELECT item_no"), eq(9L))).thenReturn(List.of(Map.of(
                    "item_no", "ITEM-009", "description", "Item", "base_unit_of_measure", "EA")));
                controller.createPurchaseDocument(new ErpWriteController.PurchaseDocumentCreateRequest(
                    Map.of("document_type", type, "vendor_id", "4", "order_date", "2026-10-13", "location_code", "KHO-HCM"),
                    List.of(new ErpWriteController.PurchaseDocumentLineInput(null, 9L, "Item", "EA",
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, selected))));
                verify(jdbc).queryForObject(eq("SELECT erp_next_document_no(?)"), eq(String.class),
                    eq(type.equals("ORDER") ? "PO" : "PQ"));
                var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
                verify(jdbc).update(contains("INSERT INTO purchase_lines"), values.capture());
                assertEquals(selected == null ? "KHO-HCM" : selected, values.getValue()[6]);
            }
        }
    }

    @Test
    void purchaseRejectsUnknownLineWarehouseBeforeInsertingLines() {
        when(jdbc.queryForObject(contains("FROM units_of_measure"), eq(Boolean.class), eq("EA"))).thenReturn(true);
        when(jdbc.queryForObject(contains("FROM vendors"), eq(Boolean.class), eq(4L))).thenReturn(true);
        when(jdbc.queryForObject(eq("SELECT erp_next_document_no(?)"), eq(String.class), anyString())).thenReturn("PO-001");
        when(jdbc.queryForObject(contains("INSERT INTO purchase_headers"), eq(Long.class), any(Object[].class))).thenReturn(7L);
        when(jdbc.queryForList(contains("SELECT item_no"), eq(9L))).thenReturn(List.of(Map.of(
            "item_no", "ITEM-009", "description", "Item", "base_unit_of_measure", "EA")));
        var failure = assertThrows(ResponseStatusException.class, () -> controller.createPurchaseDocument(
            new ErpWriteController.PurchaseDocumentCreateRequest(
                Map.of("document_type", "ORDER", "vendor_id", "4", "order_date", "2026-10-13"),
                List.of(new ErpWriteController.PurchaseDocumentLineInput(null, 9L, "Item", "EA",
                    BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, "MISSING")))));
        assertTrue(failure.getReason().contains("Locations"));
        verify(jdbc, never()).update(contains("INSERT INTO purchase_lines"), any(Object[].class));
    }

    @Test
    void refusesManualEditsToStockHistoryAndLegacySales() {
        for (String table : List.of("inventory_ledger", "production_components", "sales_lines")) {
            assertThrows(ResponseStatusException.class, () -> controller.create(table, Map.of()));
            assertThrows(ResponseStatusException.class, () -> controller.update(table, 1L, Map.of()));
            assertThrows(ResponseStatusException.class, () -> controller.delete(table, 1L));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void salesInvoiceAndReturnFormsRequireValidSelectedSalesLinesBeforeWriting() {
        var header = Map.<String, Object>of("sales_order_id", "1");
        assertThrows(ResponseStatusException.class, () -> controller.createSalesInvoiceWithLines(
            new ErpWriteController.SalesDocumentCreateRequest(header, List.of())));
        assertThrows(ResponseStatusException.class, () -> controller.createSalesReturnWithLines(
            new ErpWriteController.SalesDocumentCreateRequest(header, List.of())));
        assertThrows(ResponseStatusException.class, () -> controller.createSalesInvoiceWithLines(
            new ErpWriteController.SalesDocumentCreateRequest(header, List.of(
                new ErpWriteController.SalesDocumentLineInput(1L, BigDecimal.ZERO, BigDecimal.ZERO)))));
        assertThrows(ResponseStatusException.class, () -> controller.createSalesReturnWithLines(
            new ErpWriteController.SalesDocumentCreateRequest(header, List.of(
                new ErpWriteController.SalesDocumentLineInput(1L, new BigDecimal("1.0001"), null)))));
        verifyNoInteractions(jdbc);
    }

    @Test
    void purchaseDocumentHeaderInsertBindsOneValueForEveryColumn() {
        when(jdbc.queryForObject(contains("FROM units_of_measure"), eq(Boolean.class), eq("EA"))).thenReturn(true);
        when(jdbc.queryForObject(contains("FROM vendors"), eq(Boolean.class), eq(4L))).thenReturn(true);
        when(jdbc.queryForObject(eq("SELECT erp_next_document_no(?)"), eq(String.class), anyString())).thenReturn("PO-001");
        when(jdbc.queryForObject(contains("INSERT INTO purchase_headers"), eq(Long.class), any(Object[].class)))
            .thenReturn(7L);
        when(jdbc.queryForList(contains("SELECT item_no"), eq(9L))).thenReturn(List.of(Map.of(
            "item_no", "ITEM-009", "description", "Vật tư", "base_unit_of_measure", "EA")));

        var request = new ErpWriteController.PurchaseDocumentCreateRequest(
            Map.of("document_type", "ORDER", "vendor_id", "4", "order_date", "2026-10-13"),
            List.of(new ErpWriteController.PurchaseDocumentLineInput(null, 9L, "Vật tư", "EA",
                new BigDecimal("2.000"), new BigDecimal("1500000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, null)));

        assertEquals("7", controller.createPurchaseDocument(request).get("id"));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), values.capture());
        assertEquals(16, sql.getValue().replaceAll("[^?]", "").length());
        assertEquals(16, values.getValue().length);
    }

    @Test
    void productionOrderNumberIsGeneratedWithProPrefix() {
        doReturn(List.of(field("production_no", "character varying", true, false)))
            .when(controller).schema("production_orders");
        when(jdbc.queryForObject(eq("SELECT erp_next_document_no(?)"), eq(String.class), eq("PRO")))
            .thenReturn("PRO-001");
        when(jdbc.queryForObject(contains("INSERT INTO public"), eq(Object.class), any(Object[].class)))
            .thenReturn(15L);

        assertEquals("15", controller.create("production_orders", Map.of()).get("id"));

        verify(jdbc).queryForObject(eq("SELECT erp_next_document_no(?)"), eq(String.class), eq("PRO"));
        verify(jdbc).queryForObject(contains("INSERT INTO public"), eq(Object.class), eq("PRO-001"));
    }

    @Test
    void rejectsQuantityPrecisionThatWouldBeRoundedByDatabase() {
        doReturn(List.of(field("quantity", "numeric", true, false)))
            .when(controller).schema("purchase_receipt_lines");
        assertThrows(ResponseStatusException.class, () -> controller.validate(
            "purchase_receipt_lines", Map.of("quantity", "0.0001"), true));
        assertEquals(new BigDecimal("1.125"), controller.validate(
            "purchase_receipt_lines", Map.of("quantity", "1.125"), true).get("quantity"));
    }

    @Test
    void stockErrorIsReadableWithoutDatabaseInternals() {
        var cause = new java.sql.SQLException("ERROR: STOCK: Không đủ tồn kho khả dụng.\nWhere: SQL statement", "23514");
        var response = controller.constraint(new org.springframework.dao.DataIntegrityViolationException("failed", cause));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("Không đủ tồn kho khả dụng.", response.getBody().get("message"));
    }

    private ErpWriteController.Field field(String name, String type, boolean required, boolean defaults) {
        return new ErpWriteController.Field(name, type, required, defaults, null, 255, true);
    }

    @Test
    void requiredAndProtectedFieldsAreRejected() {
        doReturn(List.of(field("code", "character varying", true, false))).when(controller).schema("units_of_measure");
        assertThrows(ResponseStatusException.class, () -> controller.validate("units_of_measure", Map.of(), true));
        assertThrows(ResponseStatusException.class, () -> controller.validate("units_of_measure", Map.of("id", "1"), false));
        assertThrows(ResponseStatusException.class, () -> controller.validate("units_of_measure", Map.of("code", " "), true));
        verifyNoInteractions(jdbc);
    }

    @Test
    void preservesDefaultsAndConvertsExactDecimal() {
        doReturn(List.of(field("unit_price", "numeric", true, true), field("blocked", "boolean", false, true)))
            .when(controller).schema("items");
        assertTrue(controller.validate("items", Map.of(), true).isEmpty());
        var result = controller.validate("items", Map.of("unit_price", "123456789.1234", "blocked", false), true);
        assertEquals(new BigDecimal("123456789.1234"), result.get("unit_price"));
        assertEquals(false, result.get("blocked"));
    }

    @Test
    void itemBaseUnitMustExistAndBeActiveInUnitsOfMeasure() {
        doReturn(List.of(field("base_unit_of_measure", "character varying", false, false)))
            .when(controller).schema("items");
        when(jdbc.queryForObject(contains("FROM units_of_measure"), eq(Boolean.class), eq("EA"))).thenReturn(true);
        assertEquals("EA", controller.validate("items", Map.of("base_unit_of_measure", "EA"), true)
            .get("base_unit_of_measure"));

        when(jdbc.queryForObject(contains("FROM units_of_measure"), eq(Boolean.class), eq("MISSING"))).thenReturn(false);
        var failure = assertThrows(ResponseStatusException.class,
            () -> controller.validate("items", Map.of("base_unit_of_measure", "MISSING"), true));
        assertTrue(failure.getReason().contains("Đơn vị tính cơ bản"));
    }

    @Test
    void rejectsInvalidTypesAndEmail() {
        doReturn(List.of(field("quantity", "numeric", false, false), field("email", "character varying", false, false)))
            .when(controller).schema("items");
        assertThrows(ResponseStatusException.class, () -> controller.validate("items", Map.of("quantity", "abc"), true));
        assertThrows(ResponseStatusException.class, () -> controller.validate("items", Map.of("email", "abc"), true));
    }

    @Test
    void createBindsValuesInsteadOfInterpolatingSql() {
        doReturn(List.of(field("code", "character varying", true, false))).when(controller).schema("units_of_measure");
        when(jdbc.queryForObject(anyString(), eq(Object.class), any(Object[].class))).thenReturn(9L);
        assertEquals("9", controller.create("units_of_measure", Map.of("code", "quote'code")).get("id"));
        verify(jdbc).queryForObject(eq("INSERT INTO public.\"units_of_measure\" (\"code\") VALUES (?) RETURNING id"), eq(Object.class), eq("quote'code"));
    }

    @Test
    void acceptsOnlySupportedItemCategoriesAndOrigins() {
        doReturn(List.of(field("item_category", "character varying", true, true),
            field("item_type", "character varying", true, true))).when(controller).schema("items");

        assertEquals("SERVICE", controller.validate("items",
            Map.of("item_category", "SERVICE", "item_type", "PURCHASED"), true).get("item_category"));
        assertThrows(ResponseStatusException.class, () -> controller.validate("items",
            Map.of("item_category", "UNKNOWN", "item_type", "PURCHASED"), true));
        assertThrows(ResponseStatusException.class, () -> controller.validate("items",
            Map.of("item_category", "INVENTORY", "item_type", "UNKNOWN"), true));
    }

    @Test
    void allowsCreatingItemsFromTheItemForm() {
        doReturn(List.of(field("item_no", "character varying", true, false),
            field("description", "character varying", false, false))).when(controller).schema("items");
        when(jdbc.queryForObject(anyString(), eq(Object.class), any(Object[].class))).thenReturn(12L);

        assertEquals("12", controller.create("items", Map.of("item_no", "ITEM-012",
            "description", "Hàng hóa mới")).get("id"));

        verify(jdbc).queryForObject(eq("INSERT INTO public.\"items\" (\"item_no\",\"description\") VALUES (?,?) RETURNING id"),
            eq(Object.class), eq("ITEM-012"), eq("Hàng hóa mới"));
    }

    @Test
    void createsInventoryItemAndRecordsOpeningQuantityAtSelectedWarehouse() {
        doReturn(List.of(field("item_no", "character varying", true, false),
            field("item_category", "character varying", true, true),
            field("item_type", "character varying", true, true),
            field("unit_cost", "numeric", false, false))).when(controller).schema("items");
        when(jdbc.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class), eq(8L))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Object.class), any(Object[].class))).thenReturn(42L);

        assertEquals("42", controller.create("items", Map.of("item_no", "ITEM-042",
            "item_category", "INVENTORY", "item_type", "PURCHASED", "initial_location_id", "8",
            "initial_quantity", "12.500")).get("id"));

        verify(jdbc).update(contains("INSERT INTO inventory(item_id,location_id,quantity,average_cost)"),
            eq(42L), eq(8L), eq(new BigDecimal("12.500")), eq(42L));
    }

    @Test
    void rejectsOpeningStockForNonInventoryItems() {
        doReturn(List.of(field("item_no", "character varying", true, false),
            field("item_category", "character varying", true, true))).when(controller).schema("items");

        assertThrows(ResponseStatusException.class, () -> controller.create("items", Map.of("item_no", "SERVICE-01",
            "item_category", "SERVICE", "initial_location_id", "8", "initial_quantity", "1")));

        verify(jdbc, never()).queryForObject(anyString(), eq(Object.class), any(Object[].class));
    }

    @Test
    void updateOnlyChangesSubmittedColumns() {
        doReturn(List.of(field("name", "character varying", true, false))).when(controller).schema("units_of_measure");
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(Map.of("id", 7L, "name", "Old")));
        controller.update("units_of_measure", 7, Map.of("name", "New"));
        verify(jdbc).update("UPDATE public.\"units_of_measure\" SET \"name\" = ? WHERE id = ?", "New", 7L);
    }

    @Test
    void preventsCascadeDeleteOfReferencedRows() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(7L))).thenReturn(List.of(Map.of("id", 7L)));
        when(jdbc.queryForList(contains("pg_constraint"), eq("locations")))
            .thenReturn(List.of(Map.of("child_table", "inventory", "child_column", "location_id")));
        when(jdbc.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq(7L))).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> controller.delete("locations", 7));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsReleasedOrderMutation() {
        doReturn(List.of(field("notes", "character varying", false, false))).when(controller).schema("sales_orders");
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(7L))).thenReturn(List.of(Map.of("id", 7L, "status", "RELEASED")));
        assertThrows(ResponseStatusException.class, () -> controller.update("sales_orders", 7, Map.of("notes", "changed")));
        assertThrows(ResponseStatusException.class, () -> controller.delete("sales_orders", 7));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void passwordIsHashedAndBlankUpdateLeavesItUnchanged() {
        doReturn(List.of(field("name", "character varying", false, false), field("email", "character varying", false, false),
            field("password", "character varying", false, false))).when(controller).schema("users");
        var result = controller.validate("users", Map.of("name", "Test", "email", "test@example.com", "password", "test-password"), true);
        assertTrue(new BCryptPasswordEncoder().matches("test-password", (String) result.get("password")));
        assertTrue(controller.validate("users", Map.of("password", ""), false).isEmpty());
        assertThrows(ResponseStatusException.class, () -> controller.validate("users", Map.of("name", "Test"), true));
    }
}
