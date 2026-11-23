package org.example.erp_module.controller;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
@RestController @RequestMapping("/api/erp/sales-accounting")
public class SalesAccountingController {
 private final JdbcTemplate db;public SalesAccountingController(JdbcTemplate db){this.db=db;}
 public record Payment(UUID requestKey,LocalDate postingDate,BigDecimal amount,String reference){}
 private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
 private void manager(){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||a.getAuthorities().stream().noneMatch(x->Set.of("ROLE_ADMIN","ROLE_DIRECTOR").contains(x.getAuthority())))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chỉ Admin/Director được ghi nhận thu tiền hoặc cấu hình hạch toán.");}
 @GetMapping("/{key}") @Transactional(readOnly=true)
 public Map<String,Object> state(@PathVariable long key){var rows=db.queryForList("SELECT i.*,p.posted_at,p.inventory_cost FROM sales_invoices i LEFT JOIN sales_postings p ON p.invoice_id=i.id WHERE i.id=?",key);if(rows.isEmpty())throw bad("Không tìm thấy hóa đơn.");return Map.of("invoice",rows.getFirst(),"balance",balance(key),"payments",db.queryForList("SELECT p.id::text AS id,p.payment_no,s.amount,s.posting_date,s.reference FROM sales_settlements s JOIN payments p ON p.id=s.payment_id WHERE s.invoice_id=? ORDER BY s.id",key));}
 private BigDecimal balance(long key){return db.queryForObject("SELECT total_amount-coalesce((SELECT sum(amount) FROM sales_settlements WHERE invoice_id=i.id),0) FROM sales_invoices i WHERE id=?",BigDecimal.class,key);}
 @GetMapping("/setup") public Map<String,Object> setup(){return Map.of("mappings",db.queryForList("SELECT s.role,s.account_id,a.account_no,a.account_name FROM sales_posting_setup s JOIN accounts a ON a.id=s.account_id ORDER BY role"),"accounts",db.queryForList("SELECT id,account_no,account_name FROM accounts WHERE active ORDER BY account_no"));}
 @PutMapping("/setup") @Transactional public void setup(@RequestBody Map<String,Long> input){manager();db.execute("SELECT pg_advisory_xact_lock(7821348)");if(!input.keySet().equals(Set.of("RECEIVABLE","REVENUE","VAT","COGS","INVENTORY","FINISHED","BANK")))throw bad("Cấu hình đủ 7 tài khoản Sales.");for(var entry:input.entrySet()){if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND active)",Boolean.class,entry.getValue())))throw bad("Tài khoản không hoạt động.");db.update("UPDATE sales_posting_setup SET account_id=? WHERE role=?",entry.getValue(),entry.getKey());}}
 @PostMapping("/{key}/pay") @Transactional public Map<String,Object> pay(@PathVariable long key,@RequestBody Payment input){
  manager();if(input==null||input.requestKey()==null||input.amount()==null||input.amount().signum()<=0||input.amount().stripTrailingZeros().scale()>2||input.reference()==null||input.reference().isBlank()||input.reference().length()>255)throw bad("Nhập số tiền dương, tối đa 2 số lẻ và nội dung thu tiền.");
  db.execute("SELECT pg_advisory_xact_lock(7821348)");LocalDate date=input.postingDate()==null?LocalDate.now():input.postingDate();var old=db.queryForList("SELECT * FROM sales_settlements WHERE request_key=?",input.requestKey());
  if(!old.isEmpty()){var p=old.getFirst();if(((Number)p.get("invoice_id")).longValue()!=key||((BigDecimal)p.get("amount")).compareTo(input.amount())!=0||!p.get("reference").equals(input.reference())||!p.get("posting_date").toString().equals(date.toString()))throw bad("Mã yêu cầu đã dùng cho nội dung khác.");return Map.of("id",p.get("payment_id").toString());}
  var rows=db.queryForList("SELECT i.* FROM sales_invoices i JOIN sales_postings p ON p.invoice_id=i.id WHERE i.id=? FOR UPDATE OF i",key);if(rows.isEmpty())throw bad("Hóa đơn chưa được ghi sổ tự động; không thu tiền qua luồng mới.");var h=rows.getFirst();if(date.isBefore(((java.sql.Date)h.get("posting_date")).toLocalDate()))throw bad("Ngày thu tiền không trước ngày ghi sổ hóa đơn.");if(input.amount().compareTo(balance(key))>0)throw bad("Số tiền thu vượt công nợ còn lại.");
  String no=db.queryForObject("SELECT 'PT-'||lpad(n::text,greatest(3,length(n::text)),'0') FROM (SELECT nextval('sales_payment_no_seq') AS n) s",String.class);long account=db.queryForObject("SELECT erp_sales_account('BANK')",Long.class);
  long payment=db.queryForObject("INSERT INTO payments(payment_no,payment_type,customer_id,payment_date,amount,payment_method,account_id,description,status) VALUES(?,'RECEIPT',?,?,?,'BANK',?,?,'POSTED') RETURNING id",Long.class,no,h.get("customer_id"),java.sql.Date.valueOf(date),input.amount(),account,input.reference());
  long entry=db.queryForObject("INSERT INTO customer_ledger(entry_no,customer_id,document_type,document_id,posting_date,debit,credit,balance,description) VALUES(?,?,'SALES_PAYMENT',?,?,0,?,0,?) RETURNING id",Long.class,no,h.get("customer_id"),key,java.sql.Date.valueOf(date),input.amount(),input.reference());
  db.update("INSERT INTO sales_settlements(invoice_id,request_key,posting_date,amount,reference,customer_entry_id,payment_id) VALUES(?,?,?,?,?,?,?)",key,input.requestKey(),java.sql.Date.valueOf(date),input.amount(),input.reference(),entry,payment);
  db.update("INSERT INTO payment_allocations(payment_id,sales_invoice_id,allocated_amount) VALUES(?,?,?)",payment,key,input.amount());
  db.queryForObject("SELECT erp_sales_gl(?,?,?,?,?,?)",Object.class,key,no,"SALES_PAYMENT",java.sql.Date.valueOf(date),"BANK",input.amount());db.queryForObject("SELECT erp_sales_gl(?,?,?,?,?,?)",Object.class,key,no,"SALES_PAYMENT",java.sql.Date.valueOf(date),"RECEIVABLE",input.amount().negate());
  return Map.of("id",Long.toString(payment));
 }
}
