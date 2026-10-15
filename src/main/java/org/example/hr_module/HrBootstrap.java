package org.example.hr_module;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HrBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public HrBootstrap(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void run(ApplicationArguments args) throws java.io.IOException {
        jdbc.execute("SELECT pg_advisory_xact_lock(7821346)");
        new ResourceDatabasePopulator(new ClassPathResource("hr-schema.sql")).execute(jdbc.getDataSource());
        try (var stream = new ClassPathResource("hr-notifications.sql").getInputStream()) {
            jdbc.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
