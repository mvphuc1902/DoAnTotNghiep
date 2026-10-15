package org.example.hr_module;

import org.example.user_module.config.DatabaseUsers;
import org.example.user_module.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest
@ContextConfiguration(classes={SecurityConfig.class,HrWorkspaceController.class})
@WithMockUser(username="hr",roles="HR")
class HrEmployeeAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean DatabaseUsers users;
    private static final String DATA="""
        {"employeeCode":" NV-101 ","name":" Test Employee ","email":"employee@example.test",
         "phone":"0901234567","department":"HR","position":"Specialist","hireDate":"2026-09-01","active":true}
        """;
    @BeforeEach void setup() {
        for(String role:new String[]{"HR","EMPLOYEE","ADMIN","DIRECTOR"})
            when(users.loadUserByUsername(role.toLowerCase())).thenReturn(User.withUsername(role.toLowerCase()).password("password").roles(role).build());
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(42L);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
    }
    @Test void hrCanCreateEmployee() throws Exception {
        mvc.perform(post("/api/hr/employees").with(csrf()).contentType("application/json").content(DATA))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(42));
        verify(jdbc).queryForObject(contains("INSERT INTO employees"),eq(Long.class),eq("NV-101"),eq("Test Employee"),
            eq("employee@example.test"),eq("0901234567"),eq("HR"),eq("Specialist"),eq(java.time.LocalDate.of(2026,9,1)),eq(true));
    }
    @Test void hrCanEditEmployeeWithoutChangingLinkedAccount() throws Exception {
        mvc.perform(put("/api/hr/employees/42").with(csrf()).contentType("application/json").content(DATA))
            .andExpect(status().isOk());
        verify(jdbc).update(startsWith("UPDATE employees SET employee_code"),any(Object[].class));
        verify(jdbc,never()).update(contains("user_id="),any(Object[].class));
    }
    @Test void hrCanDeleteEmployeeProfile() throws Exception {
        mvc.perform(delete("/api/hr/employees/42").with(csrf())).andExpect(status().isNoContent());
        verify(jdbc).update("DELETE FROM employees WHERE id=?",42L);
        verify(jdbc,never()).update(contains("DELETE FROM users"),any(Object[].class));
    }
    @Test void missingEmployeeReturns404() throws Exception {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        mvc.perform(delete("/api/hr/employees/999").with(csrf())).andExpect(status().isNotFound());
    }
    @Test void invalidFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/hr/employees").with(csrf()).contentType("application/json")
            .content(DATA.replace("NV-101"," "))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/hr/employees").with(csrf()).contentType("application/json")
            .content(DATA.replace("employee@example.test","bad-email"))).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }
    @Test void duplicateEmployeeCodeHasReadableError() throws Exception {
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenThrow(
            new org.springframework.dao.DataIntegrityViolationException("duplicate",new java.sql.SQLException("duplicate","23505")));
        mvc.perform(post("/api/hr/employees").with(csrf()).contentType("application/json").content(DATA))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Mã nhân viên hoặc thông tin định danh đã tồn tại."));
    }
    @Test void writesStillRequireCsrf() throws Exception {
        mvc.perform(delete("/api/hr/employees/42")).andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }
    @Test @WithMockUser(username="employee",roles="EMPLOYEE")
    void employeeCannotManageDirectory() throws Exception {
        mvc.perform(get("/api/hr/employees")).andExpect(status().isForbidden());
        mvc.perform(post("/api/hr/employees").with(csrf()).contentType("application/json").content(DATA)).andExpect(status().isForbidden());
        mvc.perform(put("/api/hr/employees/42").with(csrf()).contentType("application/json").content(DATA)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/hr/employees/42").with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }
    @Test void hrStillCannotManageAccountsOrBusinessData() throws Exception {
        mvc.perform(delete("/api/erp/tables/users/42").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/customers/42").with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }
    @Test @WithMockUser(username="director",roles="DIRECTOR")
    void directorKeepsEmployeeManagement() throws Exception {
        mvc.perform(delete("/api/hr/employees/42").with(csrf())).andExpect(status().isNoContent());
    }
}
