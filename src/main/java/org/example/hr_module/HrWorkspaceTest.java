package org.example.hr_module;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class HrWorkspaceTest {
    @Test void staffCannotReadEmployeeDirectoryOrWritePosts() {
        var jdbc=mock(JdbcTemplate.class);var c=new HrWorkspaceController(jdbc);
        assertThrows(ResponseStatusException.class,()->c.employees(HrControllerTest.auth("EMPLOYEE")));
        assertThrows(ResponseStatusException.class,()->c.create(HrControllerTest.auth("EMPLOYEE"),null));
        verifyNoInteractions(jdbc);
    }
    @Test void staffReadsPublishedOnly() {
        var jdbc=mock(JdbcTemplate.class);new HrWorkspaceController(jdbc).posts(HrControllerTest.auth("EMPLOYEE"));
        verify(jdbc).queryForList(contains("WHERE p.status='PUBLISHED'"));
    }
    @Test void hrCanReadEmployeeProfiles() {
        var jdbc=mock(JdbcTemplate.class);new HrWorkspaceController(jdbc).employees(HrControllerTest.auth("HR"));
        verify(jdbc).queryForList(contains("FROM employees e LEFT JOIN users"));
    }
    @Test void senderDefaultsDoNotEnableMailWithoutPassword() {
        var env=new MockEnvironment().withProperty("erp.mail.from","tngoctran040503@gmail.com").withProperty("erp.mail.host","smtp.gmail.com").withProperty("erp.mail.username","tngoctran040503@gmail.com");
        var mailer=new PayrollMailer(env);assertEquals("tngoctran040503@gmail.com",mailer.sender());assertFalse(mailer.configured());
        env.setProperty("ERP_MAIL_FROM","override@example.test");assertEquals("override@example.test",mailer.sender());
    }
}
