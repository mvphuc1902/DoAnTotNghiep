package org.example.salesorder_module.repository;

import org.example.salesorder_module.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SalesOrderLineRepository
        extends JpaRepository<SalesOrderLine, Long> {
}
