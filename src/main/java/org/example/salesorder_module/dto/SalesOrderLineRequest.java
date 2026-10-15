package org.example.salesorder_module.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SalesOrderLineRequest {

    private Long itemId;

    private BigDecimal quantity;

    private String unitOfMeasure;

    private BigDecimal unitPrice;

    private BigDecimal discountPercent;

    private BigDecimal vatPercent;
}