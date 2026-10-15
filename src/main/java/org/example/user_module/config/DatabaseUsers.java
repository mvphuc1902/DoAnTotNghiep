package org.example.user_module.config;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
@Service
public class DatabaseUsers implements UserDetailsService {
    private final JdbcTemplate jdbc;
    public DatabaseUsers(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public UserDetails loadUserByUsername(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT username,password,role,dashboard_access FROM users WHERE lower(username)=lower(?)", username.trim());
        if (rows.size() != 1) throw new UsernameNotFoundException("Invalid account");
        var row = rows.getFirst();
        String password = (String) row.get("password");
        if (password == null || password.isBlank()) throw new UsernameNotFoundException("Account has no password");
        final AccountRole role;
        try { role = AccountRole.fromStored((String) row.get("role")); }
        catch (IllegalArgumentException | NullPointerException error) { throw new UsernameNotFoundException("Invalid account role"); }
        List<String> authorities = new ArrayList<>();
        authorities.add("ROLE_" + role.name());
        if (role == AccountRole.ADMIN || role == AccountRole.DIRECTOR || Boolean.TRUE.equals(row.get("dashboard_access")))
            authorities.add("VIEW_DASHBOARD");
        return User.withUsername((String) row.get("username")).password(password)
            .authorities(authorities.toArray(String[]::new)).build();
    }
}
