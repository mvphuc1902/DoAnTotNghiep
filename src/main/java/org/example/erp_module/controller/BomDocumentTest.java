package org.example.erp_module.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BomDocumentTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ErpWriteController controller = new ErpWriteController(jdbc);
    ErpWriteController.BomLine line(long id, String quantity) { return new ErpWriteController.BomLine(id, new BigDecimal(quantity)); }
    @Test void rejectsDuplicateAndSelfComponentsBeforeWriting() {
        assertThrows(ResponseStatusException.class, () -> controller.saveBom(1, new ErpWriteController.BomDocument(List.of(), List.of(line(2,"1"), line(2,"2")))));
        assertThrows(ResponseStatusException.class, () -> controller.saveBom(1, new ErpWriteController.BomDocument(List.of(), List.of(line(1,"1")))));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsInvalidQuantities() {
        for (String value : List.of("0", "-1", "0.0001"))
            assertThrows(ResponseStatusException.class, () -> controller.saveBom(1, new ErpWriteController.BomDocument(List.of(), List.of(line(2,value)))));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsStaleDocumentWithoutChangingLines() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(1L))).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("component_item_id",2L,"quantity",new BigDecimal("3"))));
        var error = assertThrows(ResponseStatusException.class, () -> controller.saveBom(1, new ErpWriteController.BomDocument(List.of(line(2,"1")), List.of(line(2,"2")))));
        assertEquals(409,error.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void savesMultipleLinesAndAcceptsEquivalentDecimalScales() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(1L))).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("component_item_id",2L,"quantity",new BigDecimal("1.000"))));
        var result=controller.saveBom(1,new ErpWriteController.BomDocument(List.of(line(2,"1")),List.of(line(2,"2"),line(3,"0.25"))));
        assertEquals(2,result.get("lineCount"));
        verify(jdbc).update(startsWith("INSERT INTO item_bom"),eq(1L),eq(2L),eq(new BigDecimal("2")));
        verify(jdbc).update(startsWith("INSERT INTO item_bom"),eq(1L),eq(3L),eq(new BigDecimal("0.25")));
    }
}
