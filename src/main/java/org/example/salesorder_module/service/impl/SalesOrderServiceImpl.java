package org.example.salesorder_module.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.customer_module.repository.CustomerRepository;
import org.example.items_module.repository.ItemRepository;
import org.example.location_module.repository.LocationRepository;
import org.example.salesorder_module.entity.SalesOrder;
import org.example.salesorder_module.repository.SalesOrderRepository;
import org.example.salesorder_module.entity.SalesOrderLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SalesOrderServiceImpl implements org.example.salesorder_module.service.SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final CustomerRepository customerRepository;
    private final ItemRepository itemRepository;
    private final LocationRepository locationRepository;

    @Override
    public List<SalesOrder> getAll() {
        return salesOrderRepository.findAll();
    }

    @Override
    public SalesOrder getById(Long id) {
        return salesOrderRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Sales Order not found: " + id));
    }

    @Override
    public SalesOrder getByOrderNo(String orderNo) {
        return salesOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Sales Order not found: " + orderNo
                        ));
    }

    @Override
    @Transactional
    public SalesOrder create(SalesOrder order) {
        return salesOrderRepository.save(prepare(order));
    }

    public SalesOrder prepare(SalesOrder order) {
        if (order.getId() != null) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "Không được truyền ID khi tạo đơn bán.");

        if (order.getCustomer() == null ||
                order.getCustomer().getId() == null) {

            throw new RuntimeException("Customer is required");
        }

        var customer = customerRepository
                .findById(order.getCustomer().getId())
                .orElseThrow(() ->
                        new RuntimeException("Customer not found"));

        order.setCustomer(customer);

        if (order.getLocation() != null &&
                order.getLocation().getId() != null) {

            var location = locationRepository
                    .findById(order.getLocation().getId())
                    .orElseThrow(() ->
                            new RuntimeException("Location not found"));

            order.setLocation(location);
        }

        if (order.getOrderNo() == null ||
                order.getOrderNo().isBlank()) {

            order.setOrderNo(generateOrderNo());
        }

//        if (order.getDocumentType() == null) {
//            order.setDocumentType("ORDER");
//        }

        if (order.getStatus() == null) {
            order.setStatus("OPEN");
        }

        if (order.getOrderDate() == null) {
            order.setOrderDate(LocalDate.now());
        }

        if (order.getPostingDate() == null) {
            order.setPostingDate(LocalDate.now());
        }

//        order.setCreatedAt(LocalDateTime.now());

        if (order.getLines() != null) {

            int lineNo = 10000;

            for (SalesOrderLine line : order.getLines()) {
                if (line.getId() != null || line.getQuantity() == null || line.getQuantity().signum() <= 0
                        || line.getQuantity().stripTrailingZeros().scale() > 3)
                    throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Dòng mới cần số lượng dương, tối đa 3 chữ số thập phân và không có ID.");

                line.setSalesOrder(order);

                if (line.getLineNo() == null) {
                    line.setLineNo(lineNo);
                }

                lineNo += 10000;

                if (line.getItem() == null ||
                        line.getItem().getId() == null) {

                    throw new RuntimeException(
                            "Item is required for line "
                                    + line.getLineNo()
                    );
                }

                var item = itemRepository
                        .findById(line.getItem().getId())
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Item not found: "
                                                + line.getItem().getId()
                                ));

                line.setItem(item);

                if (line.getQuantity() == null) {
                    line.setQuantity(BigDecimal.ZERO);
                }

                if (line.getUnitPrice() == null) {
                    line.setUnitPrice(BigDecimal.ZERO);
                }

                if (line.getDiscountPercent() == null) {
                    line.setDiscountPercent(BigDecimal.ZERO);
                }

                if (line.getVatPercent() == null) {
                    line.setVatPercent(BigDecimal.ZERO);
                }

                if (line.getUnitPrice().signum() < 0 || line.getUnitPrice().stripTrailingZeros().scale() > 2
                        || line.getDiscountPercent().signum() < 0 || line.getDiscountPercent().compareTo(new BigDecimal("100")) > 0
                        || line.getVatPercent().signum() < 0 || line.getVatPercent().compareTo(new BigDecimal("100")) > 0
                        || line.getDiscountPercent().stripTrailingZeros().scale() > 2
                        || line.getVatPercent().stripTrailingZeros().scale() > 2)
                    throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Đơn giá phải không âm; chiết khấu và VAT từ 0 đến 100, tối đa 2 chữ số thập phân.");

                BigDecimal gross = line.getQuantity().multiply(line.getUnitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
                line.setDiscountAmount(gross.multiply(line.getDiscountPercent()).movePointLeft(2)
                    .setScale(2, java.math.RoundingMode.HALF_UP));
                line.setLineAmount(gross.subtract(line.getDiscountAmount()));
                line.setVatAmount(line.getLineAmount().multiply(line.getVatPercent()).movePointLeft(2)
                    .setScale(2, java.math.RoundingMode.HALF_UP));

                line.setQuantityShipped(BigDecimal.ZERO);
                line.setQuantityInvoiced(BigDecimal.ZERO);
//                line.setCreatedAt(LocalDateTime.now());
            }
        }

        calculateOrder(order);

        return order;
    }

//    private void calculateLine(SalesLine line) {
//
//        BigDecimal gross =
//                line.getQuantity()
//                        .multiply(line.getUnitPrice());
//
//        BigDecimal discount =
//                gross.multiply(line.getDiscountPercent())
//                        .divide(
//                                BigDecimal.valueOf(100),
//                                2,
//                                java.math.RoundingMode.HALF_UP
//                        );
//
//        BigDecimal base =
//                gross.subtract(discount);
//
//        BigDecimal vat =
//                base.multiply(line.getVatPercent())
//                        .divide(
//                                BigDecimal.valueOf(100),
//                                2,
//                                java.math.RoundingMode.HALF_UP
//                        );
//
//        line.setDiscountAmount(discount);
//        line.setVatAmount(vat);
//        line.setLineAmount(base);
//    }

private void calculateOrder(SalesOrder order) {

    BigDecimal gross = BigDecimal.ZERO;      // Tổng trước VAT (chưa trừ CK)
    BigDecimal discount = BigDecimal.ZERO;   // Chiết khấu
    BigDecimal vat = BigDecimal.ZERO;        // Thuế VAT

    if (order.getLines() != null) {
        for (SalesOrderLine line : order.getLines()) {
            // lineAmount = gross - discount nên gross = lineAmount + discountAmount
            gross = gross.add(line.getLineAmount().add(line.getDiscountAmount()));
            discount = discount.add(line.getDiscountAmount());
            vat = vat.add(line.getVatAmount());
        }
    }

    order.setSubtotal(gross);
    order.setDiscountAmount(discount);
    order.setVatAmount(vat);
    order.setTotalAmount(gross.subtract(discount).add(vat)); // Tổng thanh toán
}

    @Override
    @Transactional
    public SalesOrder update(
            Long id,
            SalesOrder data) {

        SalesOrder order = getById(id);

        order.setRequestedDeliveryDate(
                data.getRequestedDeliveryDate()
        );

        order.setLocation(data.getLocation());
        order.setCurrencyCode(data.getCurrencyCode());
        order.setPaymentTermsCode(data.getPaymentTermsCode());
//        order.setShipmentMethodCode(data.getShipmentMethodCode());
        order.setSalespersonCode(data.getSalespersonCode());
//        order.setExternalDocumentNo(data.getExternalDocumentNo());
//        order.setYourReference(data.getYourReference());
//        order.setComment(data.getComment());

//        order.setUpdatedAt(LocalDateTime.now());

        return salesOrderRepository.save(order);
    }

    @Override
    @Transactional
    public void delete(Long id) {

        SalesOrder order = getById(id);

        if ("RELEASED".equals(order.getStatus())) {
            throw new RuntimeException(
                    "Released Sales Order cannot be deleted"
            );
        }

        salesOrderRepository.delete(order);
    }

    private String generateOrderNo() {

        return salesOrderRepository.nextOrderNo();
    }
}
