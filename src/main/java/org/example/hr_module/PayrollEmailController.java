package org.example.hr_module;

import jakarta.mail.MessagingException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/hr/payroll")
public class PayrollEmailController {
    private final JdbcTemplate jdbc;
    private final PayrollMailer mailer;
    public PayrollEmailController(JdbcTemplate jdbc,PayrollMailer mailer) { this.jdbc=jdbc;this.mailer=mailer; }
    private void authorize(Authentication a) { if(!HrController.payrollManager(a))throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private Map<String,Object> load(long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT p.*,u.name AS employee_name,u.email AS employee_email,u.username AS employee_username,u.department,u.team FROM hr_payroll p JOIN users u ON u.id=p.user_id WHERE p.id=?"+(lock?" FOR UPDATE OF p,u":""),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    static String body(Map<String,Object> p) {
        StringBuilder text=new StringBuilder("Hello ").append(p.get("employee_name")).append(",\n\nYour salary statement for ").append(p.get("pay_month").toString(),0,7).append(" (VND)\n\n");
        String[][] fields={{"method","Salary method"},{"base_rate","Base salary / unit rate"},{"standard_days","Standard working days"},{"units","Paid days / hours / task units"},{"overtime_hours","Overtime hours"},{"overtime_rate","Overtime hourly rate"},{"allowance","Allowance"},{"bonus","Bonus"},{"deductions","Deductions"},{"gross_pay","Gross pay"},{"net_pay","Net pay"},{"notes","Notes"}};
        for(var field:fields)text.append(field[1]).append(": ").append(Objects.toString(p.get(field[0]),"")).append('\n');
        return text.append("\nPlease contact your administrator if you have questions about this statement.\nERP Human Resources").toString();
    }
    private String recipient(Map<String,Object> p) {
        String email=Objects.toString(p.get("employee_email"),"").trim();
        try { PayrollMailer.address(email);return email; }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    @GetMapping("/{id}/email")
    public Map<String,Object> preview(Authentication a,@PathVariable long id) {
        authorize(a);var p=load(id,false);
        return Map.of("sender",mailer.sender(),"recipient",recipient(p),"subject","Salary statement - "+p.get("pay_month").toString().substring(0,7),"body",body(p),"html",PayslipReport.html(p),"version",p.get("updated_at").toString(),"configured",mailer.configured(),"configurationMessage",mailer.configurationProblem());
    }
    public record SendRequest(String recipient,String version) {}
    @PostMapping("/{id}/email") @Transactional
    public ResponseEntity<Map<String,String>> send(Authentication a,@PathVariable long id,@RequestBody SendRequest request) {
        authorize(a);
        if(!mailer.configured()) return ResponseEntity.status(503).body(Map.of("message",mailer.configurationProblem()));
        var p=load(id,true);String to=recipient(p),version=p.get("updated_at").toString();
        if(!to.equals(request.recipient())||!version.equals(request.version()))return ResponseEntity.status(409).body(Map.of("message","The recipient or salary has changed. Reopen the email preview."));
        if("SENT".equals(p.get("email_status"))&&Objects.equals(p.get("email_sent_version"),p.get("updated_at"))&&to.equals(p.get("email_recipient")))
            return ResponseEntity.status(409).body(Map.of("message","This salary version was already sent to this employee."));
        try {
            mailer.send(to,"Salary statement - "+p.get("pay_month").toString().substring(0,7),body(p),PayslipReport.html(p));
        } catch(jakarta.mail.AuthenticationFailedException e) {
            jdbc.update("UPDATE hr_payroll SET email_status='FAILED' WHERE id=?",id);
            return ResponseEntity.status(502).body(Map.of("message","Máy chủ email từ chối đăng nhập. Quản trị viên cần kiểm tra tài khoản và thông tin xác thực SMTP."));
        } catch(MessagingException|IllegalArgumentException e) {
            jdbc.update("UPDATE hr_payroll SET email_status='FAILED' WHERE id=?",id);
            return ResponseEntity.status(502).body(Map.of("message","SMTP did not confirm sending. Check the mail configuration and delivery logs before retrying."));
        }
        jdbc.update("UPDATE hr_payroll SET email_status='SENT',email_recipient=?,email_sent_at=current_timestamp,email_sent_version=updated_at WHERE id=?",to,id);
        return ResponseEntity.ok(Map.of("message","Salary statement accepted by the mail server for "+to+". Delivery to the inbox is not yet confirmed."));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.toString(e.getReason(),"Access denied or salary record not found."))); }
}



