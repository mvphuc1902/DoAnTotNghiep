package org.example.erp_module.controller;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SalesHeaderDetailsTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final SalesHeaderDetails details=new SalesHeaderDetails(jdbc);
    @Test void rejectsUnknownFieldsAndInvalidEmailsAndDates() {
        assertThrows(ResponseStatusException.class,()->details.validate(Map.of("total_amount","1")));
        assertThrows(ResponseStatusException.class,()->details.validate(Map.of("sell_to_email","invalid")));
        assertThrows(ResponseStatusException.class,()->details.validate(Map.of("document_date","not-a-date")));
        assertThrows(ResponseStatusException.class,()->details.validate(Map.of("document_date","2026-02-30")));
        verifyNoInteractions(jdbc);
    }
    @Test void lookupMustExistAndBeActive() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        assertThrows(ResponseStatusException.class,()->details.validate(Map.of("campaign_no","UNKNOWN")));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        assertEquals("VN",details.validate(Map.of("sell_to_country_code","VN")).get("sell_to_country_code"));
    }
    @Test void writesToSharedHeaderAndUsesBoundValues() {
        when(jdbc.queryForList(contains("sales_order_id=?"),eq(Long.class),eq(42L))).thenReturn(List.of(7L));
        details.save("sales_orders",42L,Map.of("external_document_no","PO'42","document_date","2026-10-13"));
        verify(jdbc).update(argThat(sql->sql.startsWith("UPDATE sales_headers SET")&&!sql.contains("PO'42")),any(Object[].class));
    }
    @Test void rejectsNonSalesTablesAndBadNestedPayload() {
        assertThrows(ResponseStatusException.class,()->details.headerId("users",1));
        assertThrows(ResponseStatusException.class,()->SalesHeaderDetails.extract(new HashMap<>(Map.of("document_details","invalid"))));
        verifyNoInteractions(jdbc);
    }
    @Test void noDetailsMeansNoExtraQueriesForUnrelatedCrud() {
        details.save("items",1,Map.of()); verifyNoInteractions(jdbc);
    }
}
