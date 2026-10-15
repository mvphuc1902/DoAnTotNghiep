package org.example.salesorder_module.repository;

import org.example.salesorder_module.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SalesOrderRepository
        extends JpaRepository<SalesOrder, Long> {

    Optional<SalesOrder> findByOrderNo(String orderNo);

    @org.springframework.data.jpa.repository.Query(value = "SELECT erp_next_document_no('SO')", nativeQuery = true)
    String nextOrderNo();

    boolean existsByOrderNo(String orderNo);
}