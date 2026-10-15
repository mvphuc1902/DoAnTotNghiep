package org.example.salesorder_module.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class SalesOrderRequest {

    private Long customerId;

    private LocalDate orderDate;

    private LocalDate requestedDeliveryDate;

    private String locationCode;

    private String currencyCode;

    private String paymentTerms;

    private List<SalesOrderLineRequest> lines;
}