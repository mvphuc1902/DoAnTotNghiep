package org.example.user_module.config;
import java.security.Principal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    public AuthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/csrf")
    public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/me")
    public Map<String,Object> me(Principal principal) {
        return jdbc.queryForMap("""
            SELECT u.id,u.username,u.name,u.email,u.role,u.dashboard_access,u.team,
                COALESCE(NULLIF(trim(u.department),''),
                    (SELECT NULLIF(trim(e.department),'') FROM employees e
                     WHERE e.user_id=u.id AND NULLIF(trim(e.department),'') IS NOT NULL
                     ORDER BY e.id LIMIT 1)) AS department
            FROM users u WHERE lower(u.username)=lower(?)
            """,principal.getName());
    }
}
