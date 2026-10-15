package org.example.salesorder_module.service;

import org.example.salesorder_module.entity.SalesOrder;

import java.util.List;

public interface SalesOrderService {

    // Lấy tất cả Sales Order
    List<SalesOrder> getAll();

    // Lấy Sales Order theo ID
    SalesOrder getById(Long id);

    // Lấy Sales Order theo số chứng từ
    SalesOrder getByOrderNo(String orderNo);

    // Tạo Sales Order
    SalesOrder create(SalesOrder order);

    // Cập nhật Sales Order
    SalesOrder update(Long id, SalesOrder order);

    // Xóa Sales Order
    void delete(Long id);
}