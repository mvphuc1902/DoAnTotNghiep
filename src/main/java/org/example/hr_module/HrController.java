package org.example.hr_module;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/hr")
public class HrController {
    private final JdbcTemplate jdbc;
    public HrController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    static boolean manages(Authentication a) {
        return a.getAuthorities().stream().anyMatch(r -> Set.of("ROLE_ADMIN", "ROLE_DIRECTOR").contains(r.getAuthority()));
    }
    static boolean payrollManager(Authentication a) { return manages(a) || a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_HR")); }
    private void requirePayrollManager(Authentication a) { if (!payrollManager(a)) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private void requireManager(Authentication a) { if (!manages(a)) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private long user(Authentication a) { return jdbc.queryForObject("SELECT id FROM users WHERE lower(username)=lower(?)", Long.class, a.getName()); }
    private void validUser(long id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)", Boolean.class, id)))
            throw new IllegalArgumentException("Account does not exist.");
    }
    private Map<String,Object> lock(String table, long id) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static void choice(String value, String... choices) {
        if (value == null || !Arrays.asList(choices).contains(value)) throw new IllegalArgumentException("Invalid option.");
    }
    @GetMapping("/people")
    public List<Map<String,Object>> people(Authentication a) {
        return payrollManager(a) ? jdbc.queryForList("SELECT id,name,username,department FROM users ORDER BY name,id")
            : jdbc.queryForList("SELECT id,name,username,department FROM users WHERE id=?", user(a));
    }
    @GetMapping("/leaves")
    public List<Map<String,Object>> leaves(Authentication a) {
        String sql = "SELECT l.*,u.name AS employee_name,r.name AS reviewer_name FROM hr_leave_requests l JOIN users u ON u.id=l.user_id LEFT JOIN users r ON r.id=l.reviewed_by";
        return payrollManager(a) ? jdbc.queryForList(sql + " ORDER BY l.id DESC LIMIT 500")
            : jdbc.queryForList(sql + " WHERE l.user_id=? ORDER BY l.id DESC LIMIT 500", user(a));
    }
    public record Leave(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                        @NotBlank String leaveType, @NotBlank @Size(max=2000) String reason) {}
    @PostMapping("/leaves") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> leave(Authentication a, @Valid @RequestBody Leave input) {
        choice(input.leaveType(), "ANNUAL", "UNPAID", "SICK", "OTHER");
        if (input.endDate().isBefore(input.startDate())) throw new IllegalArgumentException("End date must be on or after start date.");
        long uid = user(a);
        // Serialize submissions for the same account to prevent overlapping concurrent requests.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, uid);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM hr_leave_requests WHERE user_id=? AND status IN ('PENDING','APPROVED') AND start_date<=? AND end_date>=?)", Boolean.class, uid, input.endDate(), input.startDate())))
            throw new IllegalArgumentException("These dates overlap an existing pending or approved request.");
        return Map.of("id", jdbc.queryForObject("INSERT INTO hr_leave_requests(user_id,start_date,end_date,leave_type,reason) VALUES(?,?,?,?,?) RETURNING id", Long.class, uid, input.startDate(), input.endDate(), input.leaveType(), input.reason().trim()));
    }
    @PutMapping("/leaves/{id}") @Transactional
    public void editLeave(Authentication a,@PathVariable long id,@Valid @RequestBody Leave input) {
        long uid=user(a);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,uid);
        var row=lock("hr_leave_requests",id);
        if(number(row,"user_id")!=uid)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chỉ được sửa đơn nghỉ phép của chính mình.");
        if(!"PENDING".equals(row.get("status")))throw new IllegalArgumentException("Chỉ được sửa đơn đang chờ duyệt.");
        choice(input.leaveType(),"ANNUAL","UNPAID","SICK","OTHER");
        if(input.endDate().isBefore(input.startDate()))throw new IllegalArgumentException("Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM hr_leave_requests WHERE user_id=? AND id<>? AND status IN ('PENDING','APPROVED') AND start_date<=? AND end_date>=?)",
            Boolean.class,uid,id,input.endDate(),input.startDate())))throw new IllegalArgumentException("Ngày nghỉ bị trùng với đơn đang chờ duyệt hoặc đã được duyệt.");
        jdbc.update("UPDATE hr_leave_requests SET start_date=?,end_date=?,leave_type=?,reason=? WHERE id=? AND user_id=?",
            input.startDate(),input.endDate(),input.leaveType(),input.reason().trim(),id,uid);
    }
    public record Review(@NotBlank String status, @Size(max=2000) String note) {}
    @PutMapping("/leaves/{id}/status") @Transactional
    public void review(Authentication a, @PathVariable long id, @Valid @RequestBody Review input) {
        long uid = user(a);
        var row = lock("hr_leave_requests", id);
        if (!manages(a) && number(row,"user_id") != uid) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!"PENDING".equals(row.get("status"))) throw new IllegalArgumentException("Only pending requests can be changed.");
        if ("CANCELLED".equals(input.status())) {
            if (number(row,"user_id") != uid) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        } else {
            requireManager(a); choice(input.status(), "APPROVED", "REJECTED");
            if (number(row,"user_id") == uid) throw new IllegalArgumentException("Another administrator or director must review your request.");
        }
        jdbc.update("UPDATE hr_leave_requests SET status=?,review_note=?,reviewed_by=?,reviewed_at=current_timestamp WHERE id=?", input.status(), input.note(), uid, id);
    }
    @GetMapping("/tasks")
    public List<Map<String,Object>> tasks(Authentication a) {
        String sql = "SELECT t.*,u.name AS employee_name,u.department,u.team,c.name AS creator_name FROM hr_tasks t JOIN users u ON u.id=t.assigned_to JOIN users c ON c.id=t.created_by";
        if(manages(a))return jdbc.queryForList(sql+" ORDER BY t.id DESC LIMIT 500");
        if(departmentManager(a))return jdbc.queryForList(sql+" WHERE t.assigned_to=? OR ("+departmentScope()+") ORDER BY t.id DESC LIMIT 500",user(a),user(a));
        if(teamLeader(a))return jdbc.queryForList(sql+" WHERE t.assigned_to=? OR ("+departmentScope()+" AND "+teamScope()+" AND u.role='EMPLOYEE') ORDER BY t.id DESC LIMIT 500",user(a),user(a),user(a));
        return jdbc.queryForList(sql+" WHERE t.assigned_to=? ORDER BY t.id DESC LIMIT 500",user(a));
    }
    private static boolean departmentManager(Authentication a) { return a.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_MANAGER")); }
    private static boolean teamLeader(Authentication a) { return a.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_LEADER")); }
    // User departments are maintained by administrators. Editable employee profiles are not authorization data.
    private static String departmentScope() { return "NULLIF(lower(trim(u.department)),'')=(SELECT NULLIF(lower(trim(m.department)),'') FROM users m WHERE m.id=?) AND u.role NOT IN ('ADMIN','DIRECTOR')"; }
    private static String teamScope() { return "NULLIF(lower(trim(u.team)),'')=(SELECT NULLIF(lower(trim(m.team)),'') FROM users m WHERE m.id=?)"; }
    private boolean canAssign(Authentication a,long assignee) {
        if(manages(a))return true;
        if(departmentManager(a))return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=? AND u.role='LEADER' AND "+departmentScope()+")",Boolean.class,assignee,user(a)));
        return teamLeader(a)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=? AND u.role='EMPLOYEE' AND "+departmentScope()+" AND "+teamScope()+")",Boolean.class,assignee,user(a),user(a)));
    }
    @GetMapping("/task-people")
    public List<Map<String,Object>> taskPeople(Authentication a) {
        if(manages(a))return people(a);
        if(departmentManager(a))return jdbc.queryForList("SELECT u.id,u.name,u.username,u.department,u.team FROM users u WHERE u.role='LEADER' AND "+departmentScope()+" ORDER BY u.name,u.id",user(a));
        if(teamLeader(a))return jdbc.queryForList("SELECT u.id,u.name,u.username,u.department,u.team FROM users u WHERE u.role='EMPLOYEE' AND "+departmentScope()+" AND "+teamScope()+" ORDER BY u.name,u.id",user(a),user(a));
        return people(a);
    }
    @DeleteMapping("/leaves/{id}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLeave(Authentication a, @PathVariable long id) {
        requireManager(a); var row=lock("hr_leave_requests",id);
        if (!Set.of("CANCELLED","REJECTED").contains(row.get("status")))
            throw new IllegalArgumentException("Only cancelled or rejected requests can be deleted.");
        jdbc.update("DELETE FROM hr_leave_requests WHERE id=?",id);
    }
    public record Task(@NotBlank @Size(max=200) String title, @NotNull @Size(max=4000) String description,
                       @Positive long assignedTo, @NotNull LocalDate dueDate, @NotBlank String priority, @NotBlank String status,
                       @Min(0) @Max(100) int progress, @Size(max=4000) String workNote) {}
    private void validateTask(Task t) {
        choice(t.priority(), "LOW","NORMAL","HIGH","URGENT"); choice(t.status(),"TODO","IN_PROGRESS","DONE","CANCELLED"); validUser(t.assignedTo());
        if(t.progress()<0||t.progress()>100||("DONE".equals(t.status())&&t.progress()!=100)||("TODO".equals(t.status())&&t.progress()!=0))
            throw new IllegalArgumentException("Progress must be 0–100%; To do requires 0% and Done requires 100%.");
    }
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> task(Authentication a, @Valid @RequestBody Task input) {
        long uid = user(a);
        if (!canAssign(a,input.assignedTo())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        validateTask(input);
        return Map.of("id", jdbc.queryForObject("INSERT INTO hr_tasks(title,description,assigned_to,created_by,due_date,priority,status,progress,work_note) VALUES(?,?,?,?,?,?,?,?,?) RETURNING id", Long.class,
            input.title().trim(),input.description(),input.assignedTo(),uid,input.dueDate(),input.priority(),input.status(),input.progress(),Objects.toString(input.workNote(),"")));
    }
    @PutMapping("/tasks/{id}") @Transactional
    public void editTask(Authentication a, @PathVariable long id, @Valid @RequestBody Task input) {
        var row=lock("hr_tasks",id); long uid=user(a);
        if (!canAssign(a,number(row,"assigned_to"))) {
            if (number(row,"assigned_to")!=uid) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            if (input.assignedTo()!=number(row,"assigned_to")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            if (!input.title().equals(row.get("title")) || !input.description().equals(row.get("description")) || !input.priority().equals(row.get("priority")))
                throw new IllegalArgumentException("Update your work note, progress, deadline and status. Assignment details are managed by your department manager.");
            if("CANCELLED".equals(row.get("status"))||"CANCELLED".equals(input.status()))throw new IllegalArgumentException("Only your manager can cancel or reopen a cancelled task.");
        } else if(!canAssign(a,input.assignedTo()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        validateTask(input);
        jdbc.update("UPDATE hr_tasks SET title=?,description=?,assigned_to=?,due_date=?,priority=?,status=?,progress=?,work_note=?,updated_at=current_timestamp WHERE id=?",input.title().trim(),input.description(),input.assignedTo(),input.dueDate(),input.priority(),input.status(),input.progress(),Objects.toString(input.workNote(),""),id);
    }
    @DeleteMapping("/tasks/{id}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(Authentication a, @PathVariable long id) { requireManager(a); lock("hr_tasks",id); jdbc.update("DELETE FROM hr_tasks WHERE id=?",id); }
    @GetMapping("/payroll")
    public List<Map<String,Object>> payroll(Authentication a) {
        String sql = "SELECT p.*,u.name AS employee_name FROM hr_payroll p JOIN users u ON u.id=p.user_id";
        return payrollManager(a) ? jdbc.queryForList(sql+" ORDER BY p.pay_month DESC,p.id DESC LIMIT 500")
            : jdbc.queryForList(sql+" WHERE p.user_id=? ORDER BY p.pay_month DESC,p.id DESC LIMIT 500",user(a));
    }
    public record Pay(@Positive long userId, @NotBlank @Pattern(regexp="\\d{4}-\\d{2}") String month, String method,
                      BigDecimal baseRate, BigDecimal standardDays, BigDecimal units, BigDecimal overtimeHours,
                      BigDecimal overtimeRate, BigDecimal allowance, BigDecimal bonus, BigDecimal deductions,
                      @NotNull @Size(max=2000) String notes) {}
    @PostMapping("/payroll/preview")
    public PayrollCalculator.Result preview(Authentication a, @Valid @RequestBody Pay p) { requirePayrollManager(a); return calculate(p); }
    private PayrollCalculator.Result calculate(Pay p) {
        return PayrollCalculator.calculate(p.method(),p.baseRate(),p.standardDays(),p.units(),p.overtimeHours(),p.overtimeRate(),p.allowance(),p.bonus(),p.deductions());
    }
    @PostMapping("/payroll") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> createPay(Authentication a, @Valid @RequestBody Pay p) {
        requirePayrollManager(a); var result=calculate(p); validUser(p.userId());
        return Map.of("id",jdbc.queryForObject("INSERT INTO hr_payroll(user_id,pay_month,method,base_rate,standard_days,units,overtime_hours,overtime_rate,allowance,bonus,deductions,gross_pay,net_pay,notes,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,
            p.userId(),month(p.month()),p.method(),p.baseRate(),p.standardDays(),p.units(),p.overtimeHours(),p.overtimeRate(),p.allowance(),p.bonus(),p.deductions(),result.gross(),result.net(),p.notes(),user(a)));
    }
    @PutMapping("/payroll/{id}") @Transactional
    public void editPay(Authentication a,@PathVariable long id,@Valid @RequestBody Pay p) {
        requirePayrollManager(a); lock("hr_payroll",id); var result=calculate(p); validUser(p.userId());
        jdbc.update("UPDATE hr_payroll SET email_status='NOT_SENT',user_id=?,pay_month=?,method=?,base_rate=?,standard_days=?,units=?,overtime_hours=?,overtime_rate=?,allowance=?,bonus=?,deductions=?,gross_pay=?,net_pay=?,notes=?,updated_by=?,updated_at=current_timestamp WHERE id=?",
            p.userId(),month(p.month()),p.method(),p.baseRate(),p.standardDays(),p.units(),p.overtimeHours(),p.overtimeRate(),p.allowance(),p.bonus(),p.deductions(),result.gross(),result.net(),p.notes(),user(a),id);
    }
    public record CopyPayroll(@NotEmpty @Size(max=500) List<@NotNull @Positive Long> ids) {}
    @PostMapping("/payroll/copy-next-month") @Transactional
    public Map<String,Object> copyPayroll(Authentication a, @Valid @RequestBody CopyPayroll request) {
        requirePayrollManager(a);
        long actor=user(a);
        List<Map<String,Object>> results=new ArrayList<>();
        for(Long id:new TreeSet<>(request.ids())) {
            var sources=jdbc.queryForList("SELECT id,user_id,pay_month FROM hr_payroll WHERE id=? FOR UPDATE",id);
            if(sources.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Phiếu lương không còn tồn tại: "+id);
        }
        // Only the explicitly selected, locked source IDs are copied.
        for(Long id:new TreeSet<>(request.ids())) {
            var created=jdbc.queryForList("""
                INSERT INTO hr_payroll(user_id,pay_month,method,base_rate,standard_days,units,overtime_hours,
                    overtime_rate,allowance,bonus,deductions,gross_pay,net_pay,notes,updated_by)
                SELECT user_id,(pay_month+interval '1 month')::date,method,base_rate,standard_days,units,overtime_hours,
                    overtime_rate,allowance,bonus,deductions,gross_pay,net_pay,notes,?
                FROM hr_payroll WHERE id=?
                ON CONFLICT(user_id,pay_month) DO NOTHING RETURNING id,pay_month
                """,actor,id);
            results.add(Map.of("sourceId",id,"status",created.isEmpty()?"SKIPPED":"CREATED"));
        }
        return Map.of("results",results,"created",results.stream().filter(r->r.get("status").equals("CREATED")).count(),
            "skipped",results.stream().filter(r->r.get("status").equals("SKIPPED")).count());
    }
    private LocalDate month(String value) {
        try { return YearMonth.parse(value).atDay(1); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid payroll month."); }
    }
    @DeleteMapping("/payroll/{id}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePay(Authentication a,@PathVariable long id) { requireManager(a); lock("hr_payroll",id); jdbc.update("DELETE FROM hr_payroll WHERE id=?",id); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException e) { return Map.of("message",e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> validation(MethodArgumentNotValidException e) { return Map.of("message","Check the required fields, dates and text lengths."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> conflict() { return Map.of("message","Payroll already exists for this account and month, or a referenced account has changed. Refresh and try again."); }
}


