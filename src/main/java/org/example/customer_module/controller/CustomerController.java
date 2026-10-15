package org.example.customer_module.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.example.customer_module.entity.Customer;
import org.example.customer_module.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
public class CustomerController {

    @Autowired
    private CustomerRepository customerRepository;

    @GetMapping
    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    public record CustomerRequest(
        @NotBlank @Size(max = 255) String customerNo,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String address,
        @Size(max = 255) String phone,
        @Email @Size(max = 255) String email,
        @Size(max = 255) String currencyCode,
        @Size(max = 255) String paymentTermsCode,
        @Size(max = 255) String paymentMethodCode,
        Boolean blocked) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Customer create(@Valid @RequestBody CustomerRequest request) {
        return save(new Customer(), request);
    }

    @PutMapping("/{id}")
    public Customer update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        return save(find(id), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        customerRepository.delete(find(id));
        customerRepository.flush();
    }

    private Customer find(Long id) {
        return customerRepository.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy khách hàng."));
    }

    private Customer save(Customer customer, CustomerRequest request) {
        String code = request.customerNo().trim();
        customerRepository.findByCustomerNo(code).ifPresent(existing -> {
            if (customer.getId() == null || !Objects.equals(existing.getId(), customer.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã khách hàng đã tồn tại.");
            }
        });
        customer.setCustomerNo(code);
        customer.setName(request.name().trim());
        customer.setAddress(request.address());
        customer.setPhone(request.phone());
        customer.setEmail(request.email());
        customer.setCurrencyCode(request.currencyCode());
        customer.setPaymentTermsCode(request.paymentTermsCode());
        customer.setPaymentMethodCode(request.paymentMethodCode());
        customer.setBlocked(Boolean.TRUE.equals(request.blocked()));
        return customerRepository.saveAndFlush(customer);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
            "Không thể lưu hoặc xóa: mã khách hàng bị trùng hoặc khách hàng đang được sử dụng trong đơn hàng."));
    }
}
