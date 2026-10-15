package org.example.user_module.config;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.*;

@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, AuthController.class, SecurityAccessTest.Endpoints.class})
class SecurityAccessTest {
    @Test void hrCannotAccessBusinessEvenWithDashboardGrant() throws Exception {
        when(users.loadUserByUsername("hr-test")).thenReturn(User.withUsername("hr-test").password(HASH).authorities("ROLE_HR","VIEW_DASHBOARD").build());
        var session=login("hr-test");
        for(String path:java.util.List.of("/api/business","/api/erp/dashboard","/api/erp/catalog","/api/customers","/api/erp/tables/users","/api/hr/tasks","/api/hr/tasks/1","/api/hr/task-people")) {
            mvc.perform(get(path).session(session)).andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).with(csrf())).andExpect(status().isForbidden());
        }
    }
    @Test void loginPageAndCsrfArePublic() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/login.js")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"));
    }

    @Test void browserCsrfLoginAllowsIndexAndLogoutProtectsItAgain() throws Exception {
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(csrfResult.getResponse().getContentAsString());
        var loginResult = mvc.perform(post("/api/auth/login").session(session)
                .header(token.get("headerName").asText(), token.get("token").asText())
                .param("username", "admin").param("password", "test-password-123"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn();
        var authenticated = (MockHttpSession) loginResult.getRequest().getSession(false);
        mvc.perform(get("/index.html").session(authenticated)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(authenticated).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login.html"));
    }
    @Autowired MockMvc mvc;
    @MockitoBean DatabaseUsers users;
    @MockitoBean JdbcTemplate jdbc;
    private static final String HASH = new BCryptPasswordEncoder().encode("test-password-123");
    @BeforeEach void accounts() {
        when(users.loadUserByUsername("admin")).thenAnswer(call -> User.withUsername("admin").password(HASH).roles("ADMIN").build());
        when(users.loadUserByUsername("staff")).thenAnswer(call -> User.withUsername("staff").password(HASH).roles("USER").build());
    }
    @RestController
    static class Endpoints {
        @RequestMapping({"/api/business", "/api/erp/dashboard", "/api/erp/tables/users", "/api/erp/schema/users"})
        String data() { return "ok"; }
    }
    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
            .param("username",name).param("password","test-password-123"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void anonymousCannotReadBusinessOrIndex() throws Exception {
        mvc.perform(get("/api/business")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html"));
    }
    @Test void wrongPasswordFails() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username","admin").param("password","wrong"))
            .andExpect(status().isUnauthorized());
    }
    @Test void loginAlsoRequiresCsrf() throws Exception {
        mvc.perform(post("/api/auth/login").param("username","admin").param("password","test-password-123"))
            .andExpect(status().isForbidden());
    }
    @Test void adminHasDashboardAccountAndDeleteAccess() throws Exception {
        var session = login("admin");
        mvc.perform(get("/api/erp/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/erp/tables/users").session(session)).andExpect(status().isOk());
        mvc.perform(delete("/api/business").session(session).with(csrf())).andExpect(status().isOk());
    }
    @Test void staffCanCreateAndEditButCannotDeleteOrManageAccountsOrDashboard() throws Exception {
        var session = login("staff");
        mvc.perform(get("/api/business").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/business").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(put("/api/business").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete("/api/business").session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/erp/dashboard").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/erp/tables/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/erp/schema/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(put("/api/erp/tables/users").session(session).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void mutationsRequireCsrfAndLogoutInvalidatesSession() throws Exception {
        var session = login("admin");
        mvc.perform(post("/api/business").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/business")).andExpect(status().isUnauthorized());
    }
    @Test void changedRoleTakesEffectOnExistingSession() throws Exception {
        var session = login("admin");
        when(users.loadUserByUsername("admin")).thenReturn(User.withUsername("admin").password(HASH).roles("USER").build());
        mvc.perform(get("/api/erp/dashboard").session(session)).andExpect(status().isForbidden());
    }
    @Test void changedPasswordInvalidatesOldSession() throws Exception {
        var session = login("admin");
        when(users.loadUserByUsername("admin")).thenReturn(User.withUsername("admin").password("different").roles("ADMIN").build());
        mvc.perform(get("/api/business").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"DIRECTOR,200,200", "MANAGER,403,403", "LEADER,403,403", "EMPLOYEE,403,403"})
    void jobRolesHaveCorrectAccess(String role, int dashboardStatus, int deleteStatus) throws Exception {
        when(users.loadUserByUsername("role-test")).thenAnswer(call -> User.withUsername("role-test").password(HASH).roles(role).build());
        var session = login("role-test");
        mvc.perform(get("/api/erp/dashboard").session(session)).andExpect(status().is(dashboardStatus));
        mvc.perform(delete("/api/business").session(session).with(csrf())).andExpect(status().is(deleteStatus));
        mvc.perform(post("/api/business").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(put("/api/business").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/erp/tables/users").session(session)).andExpect(status().isForbidden());
    }

    @Test void dashboardGrantAndRevocationApplyToExistingSession() throws Exception {
        var session = login("staff");
        when(users.loadUserByUsername("staff")).thenAnswer(call -> User.withUsername("staff").password(HASH)
            .authorities("ROLE_EMPLOYEE", "VIEW_DASHBOARD").build());
        mvc.perform(get("/api/erp/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/erp/tables/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/business").session(session).with(csrf())).andExpect(status().isForbidden());
        when(users.loadUserByUsername("staff")).thenAnswer(call -> User.withUsername("staff").password(HASH).roles("EMPLOYEE").build());
        mvc.perform(get("/api/erp/dashboard").session(session)).andExpect(status().isForbidden());
    }
}

