package org.example.salesorder_module.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.salesorder_module.repository.SalesOrderLineRepository;
import org.example.salesorder_module.service.SalesOrderLineService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SalesOrderLineServiceImpl implements SalesOrderLineService {
    private final SalesOrderLineRepository salesOrderLineRepository;
}
