
package org.example.customer_module.entity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_no", nullable = false, unique = true)
    private String customerNo;

    @Column(nullable = false)
    private String name;

    private String address;

    private String phone;

    private String email;

    @Column(name = "currency_code")
    private String currencyCode;

    @Column(name = "payment_terms_code")
    private String paymentTermsCode;

    @Column(name = "payment_method_code")
    private String paymentMethodCode;

    private Boolean blocked = false;
}