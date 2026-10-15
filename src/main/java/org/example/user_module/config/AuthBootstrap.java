package org.example.user_module.config;
import org.springframework.boot.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class AuthBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Environment environment;
    public AuthBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, Environment environment) {
        this.jdbc = jdbc; this.encoder = encoder; this.environment = environment;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        jdbc.execute("SELECT pg_advisory_xact_lock(20260928,1001)");
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS username varchar(64)");
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role varchar(16) NOT NULL DEFAULT 'USER'");
        jdbc.update("UPDATE users SET role='EMPLOYEE' WHERE role='USER'");
        jdbc.execute("ALTER TABLE users ALTER COLUMN role SET DEFAULT 'EMPLOYEE'");
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS dashboard_access boolean NOT NULL DEFAULT false");
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS department varchar(255)");
        jdbc.update("UPDATE users SET username='user' || id WHERE username IS NULL");
        jdbc.execute("ALTER TABLE users ALTER COLUMN username SET NOT NULL");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_username_ci_unique ON users(lower(username))");
        jdbc.queryForList("SELECT id,password FROM users WHERE password IS NOT NULL").forEach(row -> {
            String password = row.get("password").toString();
            if (!password.isBlank() && !password.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$"))
                jdbc.update("UPDATE users SET password=? WHERE id=?", encoder.encode(password), row.get("id"));
        });
        Long admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN'", Long.class);
        if (admins != null && admins == 0) {
            String password = environment.getProperty("ERP_ADMIN_PASSWORD");
            if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new IllegalStateException("Set ERP_ADMIN_PASSWORD (12-72 bytes) for the first startup to create admin.");
            jdbc.update("INSERT INTO users(username,name,email,password,role) VALUES ('admin','Administrator','admin@erp.local',?,'ADMIN')", encoder.encode(password));
        }
        jdbc.update("UPDATE users SET department='Technical Solution Consultant' WHERE username='admin' AND department IS NULL");
    }
}
