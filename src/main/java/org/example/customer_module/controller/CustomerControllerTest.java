package org.example.customer_module.controller;

import java.util.List;

import org.example.customer_module.entity.Customer;
import org.example.customer_module.repository.CustomerRepository;
import org.example.user_module.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerController.class)
@ContextConfiguration(classes = {CustomerController.class, SecurityConfig.class})
@org.springframework.security.test.context.support.WithMockUser(username = "test-admin", roles = "ADMIN")
class CustomerControllerTest {
    @MockitoBean
    private org.example.user_module.config.DatabaseUsers users;

    @org.junit.jupiter.api.BeforeEach
    void authenticatedAccount() {
        when(users.loadUserByUsername("test-admin")).thenReturn(
            org.springframework.security.core.userdetails.User.withUsername("test-admin")
                .password("password").roles("ADMIN").build());
    }
    @Test
    void createsCustomerAndTrimsRequiredFields() throws Exception {
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(call -> {
            Customer customer = call.getArgument(0);
            customer.setId(10L);
            return customer;
        });
        mvc.perform(post("/api/customers").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                .content("{\"customerNo\":\" NEW-01 \",\"name\":\" New customer \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(10))
            .andExpect(jsonPath("$.customerNo").value("NEW-01"))
            .andExpect(jsonPath("$.name").value("New customer"));
    }

    @Test
    void rejectsInvalidCustomer() throws Exception {
        mvc.perform(post("/api/customers").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                .content("{\"customerNo\":\" \",\"name\":\"\",\"email\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDuplicateCode() throws Exception {
        Customer existing = new Customer();
        existing.setId(1L);
        when(repository.findByCustomerNo("DUP")).thenReturn(java.util.Optional.of(existing));
        mvc.perform(post("/api/customers").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                .content("{\"customerNo\":\"DUP\",\"name\":\"Customer\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").exists());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updatesExistingCustomer() throws Exception {
        Customer existing = new Customer();
        existing.setId(1L);
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(existing));
        when(repository.findByCustomerNo("C1")).thenReturn(java.util.Optional.of(existing));
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(call -> call.getArgument(0));
        mvc.perform(put("/api/customers/1").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                .content("{\"customerNo\":\"C1\",\"name\":\"Updated\",\"blocked\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated"))
            .andExpect(jsonPath("$.blocked").value(true));
    }

    @Test
    void missingCustomerReturns404() throws Exception {
        mvc.perform(delete("/api/customers/99").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNotFound());
    }

    @Test
    void deletesUnusedCustomer() throws Exception {
        Customer existing = new Customer();
        existing.setId(1L);
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(existing));
        mvc.perform(delete("/api/customers/1").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
        verify(repository).delete(existing);
    }

    @Test
    void reportsCustomerUsedByAnOrder() throws Exception {
        Customer existing = new Customer();
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(existing));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("foreign key"))
            .when(repository).delete(existing);
        mvc.perform(delete("/api/customers/1").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").exists());
    }
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerRepository repository;

    @Test
    void authenticatedCustomersAreAvailableFromLocalPreview() throws Exception {
        Customer customer = new Customer();
        customer.setCustomerNo("CUST-001");
        customer.setName("Khách hàng kiểm thử");
        when(repository.findAll()).thenReturn(List.of(customer));

        mvc.perform(get("/api/customers").header("Origin", "http://localhost:63342"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "*"))
            .andExpect(jsonPath("$[0].customerNo").value("CUST-001"))
            .andExpect(jsonPath("$[0].name").value("Khách hàng kiểm thử"));
    }

    @Test
    void localPreviewPreflightIsAllowed() throws Exception {
        mvc.perform(options("/api/customers")
                .header("Origin", "http://localhost:5500")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Accept"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }
}
