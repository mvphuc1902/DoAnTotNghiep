package org.example.items_module.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor 
public class Items {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_no", nullable = false, unique = true)
    private String itemNo;

    @Column(name = "description")
    private String description;

    @Column(name = "item_category", nullable = false)
    private String itemCategory = "INVENTORY";

    @Column(name = "base_unit_of_measure")
    private String baseUnitOfMeasure;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;

    @Column(name = "inventory_posting_group")
    private String inventoryPostingGroup;

    @Column(name = "gen_prod_posting_group")
    private String genProdPostingGroup;

    @Column(name = "vat_prod_posting_group")
    private String vatProdPostingGroup;

    @Column(name = "blocked")
    private Boolean blocked = false;
    @Column(name = "quantity")
    private BigDecimal quantity;
}