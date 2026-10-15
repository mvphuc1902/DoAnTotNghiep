package org.example.salesorder_module.controller;

import lombok.RequiredArgsConstructor;
import org.example.salesorder_module.dto.SalesOrderRequest;
import org.example.salesorder_module.entity.SalesOrder;
import org.example.salesorder_module.service.SalesOrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sales-orders")
@RequiredArgsConstructor
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    @GetMapping
    public List<SalesOrder> getAll() {

        return salesOrderService.getAll();
    }

    @GetMapping("/{id}")
    public SalesOrder getById(
            @PathVariable Long id) {

        return salesOrderService.getById(id);
    }

    @GetMapping("/no/{orderNo}")
    public SalesOrder getByOrderNo(
            @PathVariable String orderNo) {

        return salesOrderService.getByOrderNo(orderNo);
    }

    @PostMapping
    public SalesOrder create(
            @RequestBody SalesOrderRequest request) {

        // phần mapping Request → Entity
        return salesOrderService.create(
                mapToEntity(request)
        );
    }

    @PostMapping("/{id}/release")
    public SalesOrder release(
            @PathVariable Long id) {

        return salesOrderService.release(id);
    }

    @PostMapping("/{id}/cancel")
    public SalesOrder cancel(
            @PathVariable Long id) {

        return salesOrderService.cancel(id);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable Long id) {

        salesOrderService.delete(id);
    }

    private SalesOrder mapToEntity(
            SalesOrderRequest request) {

        SalesOrder order = new SalesOrder();

        order.setCustomer(
                new org.example.customer_module.entity.Customer()
        );

        order.getCustomer().setId(
                request.getCustomerId()
        );

        order.setOrderDate(
                request.getOrderDate()
        );

        order.setRequestedDeliveryDate(
                request.getRequestedDeliveryDate()
        );

        order.setLocationCode(
                request.getLocationCode()
        );

        order.setCurrencyCode(
                request.getCurrencyCode()
        );

        order.setPaymentTerms(
                request.getPaymentTerms()
        );

        var lines = request.getLines()
                .stream()
                .map(lineRequest -> {

                    var line =
                            new org.example.salesorderline_module.Entity.SalesOrderLine();

                    var item =
                            new org.example.items_module.entity.Items();

                    item.setId(
                            lineRequest.getItemId()
                    );

                    line.setItem(item);

                    line.setQuantity(
                            lineRequest.getQuantity()
                    );

                    line.setUnitOfMeasure(
                            lineRequest.getUnitOfMeasure()
                    );

                    line.setUnitPrice(
                            lineRequest.getUnitPrice()
                    );

                    line.setDiscountPercent(
                            lineRequest.getDiscountPercent()
                    );

                    line.setVatPercent(
                            lineRequest.getVatPercent()
                    );

                    return line;
                })
                .toList();

        order.setLines(lines);

        return order;
    }
}