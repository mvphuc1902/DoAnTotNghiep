package org.example.salesorder_module.entity;

import java.math.BigDecimal;

import org.example.items_module.entity.Items;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sales_order_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Sales Order Header
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sales_order_id", nullable = false)
    @JsonIgnore
    private SalesOrder salesOrder;

    // Line number
    @Column(name = "line_no")
    private Integer lineNo;

    // Item
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "item_id", nullable = false)
    private Items item;

    // Description
    @Column(name = "description")
    private String description;

    // Quantity
    @Column(name = "quantity", precision = 18, scale = 3)
    private BigDecimal quantity;

    // Unit of Measure
    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    // Unit Price
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    // Discount
    @Column(name = "discount_percent")
    private BigDecimal discountPercent;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    // VAT
    @Column(name = "vat_percent")
    private BigDecimal vatPercent;

    @Column(name = "vat_amount")
    private BigDecimal vatAmount;

    // Amount
    @Column(name = "line_amount")
    private BigDecimal lineAmount;

    // Shipment
    @Column(name = "quantity_shipped")
    private BigDecimal quantityShipped;

    // Invoice
    @Column(name = "quantity_invoiced")
    private BigDecimal quantityInvoiced;
}
