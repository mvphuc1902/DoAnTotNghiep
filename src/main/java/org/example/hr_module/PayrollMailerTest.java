package org.example.hr_module;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PayrollMailerTest {
    private MockEnvironment settings() {
        return new MockEnvironment().withProperty("erp.mail.host", "smtp.gmail.com")
            .withProperty("erp.mail.from", "sender@example.test")
            .withProperty("erp.mail.username", "sender@example.test")
            .withProperty("erp.mail.password", "test-secret")
            .withProperty("erp.mail.port", "587").withProperty("erp.mail.ssl", "false");
    }
    @Test void completeConfigurationEnablesSending() {
        var mailer = new PayrollMailer(settings());
        assertTrue(mailer.configured());
        assertEquals("", mailer.configurationProblem());
    }
    @Test void missingPasswordExplainsWhySendingIsDisabled() {
        var mailer = new PayrollMailer(settings().withProperty("erp.mail.password", ""));
        assertFalse(mailer.configured());
        assertTrue(mailer.configurationProblem().contains("mật khẩu"));
    }
    @Test void invalidPortSenderAndTlsFlagAreRejected() {
        for (String port : new String[]{"abc", "0", "65536"})
            assertFalse(new PayrollMailer(settings().withProperty("erp.mail.port", port)).configured());
        assertFalse(new PayrollMailer(settings().withProperty("erp.mail.from", "invalid")).configured());
        assertFalse(new PayrollMailer(settings().withProperty("erp.mail.ssl", "invalid")).configured());
    }
    @Test void environmentOverrideIsUsedWithoutExposingSecret() {
        var mailer = new PayrollMailer(settings().withProperty("erp.mail.password", "")
            .withProperty("ERP_MAIL_PASSWORD", "secret-override"));
        assertTrue(mailer.configured());
        assertFalse(mailer.configurationProblem().contains("secret-override"));
    }
    @Test void missingConfigurationFailsBeforeConnecting() {
        var mailer = new PayrollMailer(settings().withProperty("erp.mail.password", ""));
        assertThrows(jakarta.mail.MessagingException.class,
            () -> mailer.send("recipient@example.test", "Test", "Text", "<p>Text</p>"));
    }
}