package org.example.salesorder_module.entity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.example.customer_module.entity.Customer;
import org.example.location_module.entity.Location;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sales_orders")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Transient
    private String documentType;

    @Transient
    private java.util.Map<String,Object> documentDetails;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "posting_date")
    private LocalDate postingDate;

    @Column(name = "currency_code")
    private String currencyCode;

    @Column(name = "payment_terms_code")
    private String paymentTermsCode;

    @Column(name = "payment_method_code")
    private String paymentMethodCode;

    @Column(name = "salesperson_code")
    private String salespersonCode;

    private String status;

    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount")
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount")
    @Builder.Default
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "total_amount")
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    private String notes;

    @Column(name = "requested_delivery_date")
    private LocalDate requestedDeliveryDate;

    @Builder.Default
    @OneToMany(
            mappedBy = "salesOrder",
            fetch = FetchType.EAGER,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<SalesOrderLine> lines = new ArrayList<>();
}
