package org.example.salesorder_module.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.example.customer_module.entity.Customer;
import org.example.customer_module.repository.CustomerRepository;
import org.example.items_module.entity.Items;
import org.example.items_module.repository.ItemRepository;
import org.example.location_module.repository.LocationRepository;
import org.example.salesorder_module.entity.SalesOrder;
import org.example.salesorder_module.entity.SalesOrderLine;
import org.example.salesorder_module.repository.SalesOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SalesOrderDocumentTest {
    @Test void builderStartsWithZeroTotalsAndPreservesExplicitAmounts() {
        SalesOrder order = SalesOrder.builder().build();
        assertEquals(BigDecimal.ZERO, order.getSubtotal());
        assertEquals(BigDecimal.ZERO, order.getDiscountAmount());
        assertEquals(BigDecimal.ZERO, order.getVatAmount());
        assertEquals(BigDecimal.ZERO, order.getTotalAmount());
        assertEquals(new BigDecimal("1500000.50"), SalesOrder.builder()
                .totalAmount(new BigDecimal("1500000.50")).build().getTotalAmount());
    }
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ItemRepository items = mock(ItemRepository.class);
    private final SalesOrderServiceImpl service = new SalesOrderServiceImpl(orders, customers, items, mock(LocationRepository.class));

    private SalesOrder document(String quantity, String price, String discount, String vat) {
        Customer customer = new Customer(); customer.setId(1L);
        Items item = new Items(); item.setId(2L);
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(items.findById(2L)).thenReturn(Optional.of(item));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        SalesOrder order = new SalesOrder(); order.setOrderNo("TEST-DOCUMENT"); order.setCustomer(customer);
        SalesOrderLine line = new SalesOrderLine(); line.setItem(item);
        line.setQuantity(new BigDecimal(quantity)); line.setUnitPrice(new BigDecimal(price));
        line.setDiscountPercent(new BigDecimal(discount)); line.setVatPercent(new BigDecimal(vat));
        order.setLines(List.of(line)); return order;
    }

    @Test void calculatesWholeDocumentOnServerAndIgnoresSubmittedTotals() {
        SalesOrder order = document("2.5", "125000", "10", "8");
        order.setTotalAmount(new BigDecimal("1"));
        SalesOrder saved = service.create(order);
        assertEquals(new BigDecimal("31250.00"), saved.getDiscountAmount());
        assertEquals(new BigDecimal("281250.00"), saved.getSubtotal());
        assertEquals(new BigDecimal("22500.00"), saved.getVatAmount());
        assertEquals(new BigDecimal("303750.00"), saved.getTotalAmount());
        assertSame(saved, saved.getLines().getFirst().getSalesOrder());
    }

    @Test void roundsFractionalQuantityBeforeCalculatingVat() {
        SalesOrder saved = service.create(document("0.125", "1.01", "0", "10"));
        assertEquals(new BigDecimal("0.13"), saved.getSubtotal());
        assertEquals(new BigDecimal("0.01"), saved.getVatAmount());
        assertEquals(new BigDecimal("0.14"), saved.getTotalAmount());
    }

    @Test void rejectsInvalidPricesAndPercentagesBeforeSaving() {
        for (String[] values : List.of(new String[]{"-1", "0", "0"}, new String[]{"1.001", "0", "0"},
                new String[]{"1", "101", "0"}, new String[]{"1", "0", "-1"}, new String[]{"1", "0", "8.123"})) {
            SalesOrder order = document("1", values[0], values[1], values[2]);
            assertThrows(ResponseStatusException.class, () -> service.create(order));
        }
        verify(orders, never()).save(any());
    }
}
