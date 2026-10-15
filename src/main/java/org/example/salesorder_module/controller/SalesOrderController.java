package org.example.salesorder_module.controller;

import lombok.RequiredArgsConstructor;
import org.example.salesorder_module.entity.SalesOrder;
import org.example.salesorder_module.service.SalesOrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sales-orders")
@RequiredArgsConstructor
public class SalesOrderController {

    private final SalesOrderService salesOrderService;


    // GET ALL
    @GetMapping
    public List<SalesOrder> getAll() {

        return salesOrderService.getAll();
    }


    // GET BY ID
    @GetMapping("/{id}")
    public SalesOrder getById(
            @PathVariable Long id) {

        return salesOrderService.getById(id);
    }


    // GET BY ORDER NO
    @GetMapping("/no/{orderNo}")
    public SalesOrder getByOrderNo(
            @PathVariable String orderNo) {

        return salesOrderService.getByOrderNo(orderNo);
    }


    // CREATE
    @PostMapping
    public SalesOrder create(
            @RequestBody SalesOrder order) {

        return salesOrderService.create(order);
    }


    // UPDATE
    @PutMapping("/{id}")
    public SalesOrder update(
            @PathVariable Long id,
            @RequestBody SalesOrder order) {

        return salesOrderService.update(id, order);
    }


    // DELETE
    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable Long id) {

        salesOrderService.delete(id);
    }
}