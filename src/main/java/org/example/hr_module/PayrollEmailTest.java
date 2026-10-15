package org.example.hr_module;

import jakarta.mail.MessagingException;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PayrollEmailTest {
    @Test void smtpAuthenticationFailureIsActionableWithoutLeakingDetails() throws Exception {
        doThrow(new jakarta.mail.AuthenticationFailedException("private password or provider details"))
            .when(mailer).send(anyString(),anyString(),anyString(),anyString());
        var response=controller.send(HrControllerTest.auth("HR"),1,request());
        assertEquals(502,response.getStatusCode().value());
        assertTrue(response.getBody().get("message").contains("từ chối đăng nhập"));
        assertFalse(response.getBody().toString().contains("private"));
        verify(jdbc).update("UPDATE hr_payroll SET email_status='FAILED' WHERE id=?",1L);
    }
    @Test void previewExplainsDisabledSending() {
        when(mailer.configured()).thenReturn(false);
        when(mailer.configurationProblem()).thenReturn("Missing SMTP password");
        var preview=controller.preview(HrControllerTest.auth("HR"),1);
        assertEquals(false,preview.get("configured"));
        assertEquals("Missing SMTP password",preview.get("configurationMessage"));
    }
    @Test void hrCanPreviewPayslip() {
        assertEquals("employee@example.test",controller.preview(HrControllerTest.auth("HR"),1).get("recipient"));
    }
    JdbcTemplate jdbc; PayrollMailer mailer; PayrollEmailController controller;
    Map<String,Object> row;
    final Timestamp version=Timestamp.valueOf("2026-09-28 10:00:00");
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);mailer=mock(PayrollMailer.class);controller=new PayrollEmailController(jdbc,mailer);
        row=new HashMap<>(Map.of("employee_name","Employee A","employee_email","employee@example.test","pay_month","2026-09-01","updated_at",version,"email_status","NOT_SENT","net_pay","20000000"));
        when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(row));
        when(mailer.configurationProblem()).thenReturn("");when(mailer.configured()).thenReturn(true);when(mailer.sender()).thenReturn("sender@example.test");
    }
    PayrollEmailController.SendRequest request() { return new PayrollEmailController.SendRequest("employee@example.test",version.toString()); }
    @Test void employeeCannotPreviewOrSend() {
        assertThrows(ResponseStatusException.class,()->controller.preview(HrControllerTest.auth("EMPLOYEE"),1));
        assertThrows(ResponseStatusException.class,()->controller.send(HrControllerTest.auth("EMPLOYEE"),1,request()));
        verifyNoInteractions(jdbc,mailer);
    }
    @Test void missingConfigurationDoesNotSend() {
        when(mailer.configured()).thenReturn(false);when(mailer.configurationProblem()).thenReturn("Missing SMTP password");
        assertEquals(503,controller.send(HrControllerTest.auth("ADMIN"),1,request()).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
    @Test void staleRecipientAndVersionCannotSend() throws Exception {
        assertEquals(409,controller.send(HrControllerTest.auth("ADMIN"),1,new PayrollEmailController.SendRequest("other@example.test",version.toString())).getStatusCode().value());
        assertEquals(409,controller.send(HrControllerTest.auth("ADMIN"),1,new PayrollEmailController.SendRequest("employee@example.test","old")).getStatusCode().value());
        verify(mailer,never()).send(anyString(),anyString(),anyString(),anyString());
    }
    @Test void duplicateIsRejected() throws Exception {
        row.put("email_status","SENT");row.put("email_sent_version",version);row.put("email_recipient","employee@example.test");
        assertEquals(409,controller.send(HrControllerTest.auth("ADMIN"),1,request()).getStatusCode().value());
        verify(mailer,never()).send(anyString(),anyString(),anyString(),anyString());
    }
    @Test void acceptedMessageIsRecorded() throws Exception {
        assertEquals(200,controller.send(HrControllerTest.auth("DIRECTOR"),1,request()).getStatusCode().value());
        verify(mailer).send(eq("employee@example.test"),eq("Salary statement - 2026-09"),contains("Net pay: 20000000"),contains("SALARY STATEMENT"));
        verify(jdbc).update(startsWith("UPDATE hr_payroll SET email_status='SENT'"),eq("employee@example.test"),eq(1L));
    }
    @Test void smtpFailureNeverReportsSentOrLeaksCredentials() throws Exception {
        doThrow(new MessagingException("private SMTP details")).when(mailer).send(anyString(),anyString(),anyString(),anyString());
        var result=controller.send(HrControllerTest.auth("ADMIN"),1,request());
        assertEquals(502,result.getStatusCode().value());assertFalse(result.getBody().toString().contains("private SMTP"));
        verify(jdbc).update("UPDATE hr_payroll SET email_status='FAILED' WHERE id=?",1L);
    }
    @Test void previewUsesAccountEmailAndSingleStatement() {
        var result=controller.preview(HrControllerTest.auth("ADMIN"),1);
        assertEquals("employee@example.test",result.get("recipient"));assertTrue(result.get("body").toString().contains("Employee A"));
    }
    @Test void rejectsMissingMultipleAndInjectedRecipients() {
        for(String email:List.of("","bad","one@example.test,two@example.test","one@example.test\r\nBcc: other@example.test"))
            assertThrows(IllegalArgumentException.class,()->PayrollMailer.address(email));
    }
}


