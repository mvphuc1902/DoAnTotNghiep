package org.example.user_module.config;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class DatabaseUsersTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DatabaseUsers service = new DatabaseUsers(jdbc);
    private void account(String role, boolean dashboard) {
        when(jdbc.queryForList(anyString(), eq("test"))).thenReturn(List.of(Map.of("username","test","password","hash","role",role,"dashboard_access",dashboard)));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ADMIN,true", "DIRECTOR,true", "MANAGER,false", "LEADER,false", "EMPLOYEE,false", "USER,false"})
    void defaultDashboardAuthority(String role, boolean expected) {
        account(role,false);
        var user=service.loadUserByUsername("test");
        assertEquals(expected,user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("VIEW_DASHBOARD")));
        assertTrue(user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + (role.equals("USER") ? "EMPLOYEE" : role))));
    }
    @Test void explicitGrantAddsOnlyDashboardPermission() {
        account("LEADER",true);
        var authorities=service.loadUserByUsername("test").getAuthorities().stream().map(a -> a.getAuthority()).toList();
        assertEquals(List.of("ROLE_LEADER", "VIEW_DASHBOARD"),authorities);
    }
    @Test void unknownRoleCannotLogIn() {
        account("SUPERUSER",true);
        assertThrows(UsernameNotFoundException.class,() -> service.loadUserByUsername("test"));
    }
}
