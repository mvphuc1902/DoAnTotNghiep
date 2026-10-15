package org.example.hr_module;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PayslipReportTest {
    @Test void reportEscapesEmployeeContentAndFormatsAmounts() {
        var p=new HashMap<String,Object>();
        p.put("pay_month","2026-09-01");p.put("employee_name","<script>alert(1)</script>");p.put("notes","<img src=x onerror=alert(1)>");
        p.put("method","MONTHLY");p.put("base_rate","26000000");p.put("standard_days",26);p.put("units",20);p.put("gross_pay","20000000");p.put("net_pay","19000000");p.put("deductions","1000000");
        String html=PayslipReport.html(p);
        assertTrue(html.contains("19,000,000.00"));assertTrue(html.contains("SALARY STATEMENT"));assertTrue(html.contains("20 paid days / 26 standard days"));
        assertFalse(html.contains("<script>"));assertFalse(html.contains("<img"));assertTrue(html.contains("&lt;script&gt;"));
    }
    @Test void emailContainsPlainTextAndHtmlAlternatives() throws Exception {
        var mime=PayrollMailer.content("Salary plain text","<h1>Salary report</h1>");
        assertTrue(mime.getContentType().startsWith("multipart/alternative"));assertEquals(2,mime.getCount());
        assertEquals("Salary plain text",mime.getBodyPart(0).getContent());
        assertEquals("<h1>Salary report</h1>",mime.getBodyPart(1).getContent());
    }
}
