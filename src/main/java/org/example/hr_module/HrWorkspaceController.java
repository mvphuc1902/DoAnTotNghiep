package org.example.hr_module;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/hr")
public class HrWorkspaceController {
    private final JdbcTemplate jdbc;
    public HrWorkspaceController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private void manage(Authentication a) {if(!HrController.payrollManager(a))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    @GetMapping("/employees")
    public List<Map<String,Object>> employees(Authentication a) {
        manage(a);
        return jdbc.queryForList("SELECT e.id,e.employee_code,e.name,e.email,e.phone,e.department,e.position,e.hire_date,e.active,e.user_id,u.username,u.team FROM employees e LEFT JOIN users u ON u.id=e.user_id ORDER BY e.id DESC LIMIT 500");
    }
    public record Employee(@NotBlank @Size(max=50) String employeeCode,
        @NotBlank @Size(max=255) String name,@Email @Size(max=255) String email,
        @Size(max=30) String phone,@Size(max=100) String department,@Size(max=100) String position,
        java.time.LocalDate hireDate,@NotNull Boolean active) {}

    @PostMapping("/employees") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> createEmployee(Authentication a,@Valid @RequestBody Employee e) {
        manage(a);
        Long id=jdbc.queryForObject("""
            INSERT INTO employees(employee_code,name,email,phone,department,position,hire_date,active)
            VALUES(?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,e.employeeCode().trim(),e.name().trim(),optional(e.email()),optional(e.phone()),
            optional(e.department()),optional(e.position()),e.hireDate(),e.active());
        return Map.of("id",id);
    }
    @PutMapping("/employees/{id}") @Transactional
    public void editEmployee(Authentication a,@PathVariable long id,@Valid @RequestBody Employee e) {
        manage(a);
        if(jdbc.update("""
            UPDATE employees SET employee_code=?,name=?,email=?,phone=?,department=?,position=?,hire_date=?,active=? WHERE id=?
            """,e.employeeCode().trim(),e.name().trim(),optional(e.email()),optional(e.phone()),
            optional(e.department()),optional(e.position()),e.hireDate(),e.active(),id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Nhân viên không còn tồn tại.");
    }
    @DeleteMapping("/employees/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void deleteEmployee(Authentication a,@PathVariable long id) {
        manage(a);
        if(jdbc.update("DELETE FROM employees WHERE id=?",id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Nhân viên không còn tồn tại.");
    }
    private static String optional(String value) {return value==null||value.isBlank()?null:value.trim();}

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,String>> constraint(org.springframework.dao.DataIntegrityViolationException error) {
        String message="Không thể lưu hoặc xóa do dữ liệu đang được sử dụng. Kiểm tra dữ liệu liên quan.";
        if(error.getMostSpecificCause() instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()))
            message="Mã nhân viên hoặc thông tin định danh đã tồn tại.";
        return ResponseEntity.status(409).body(Map.of("message",message));
    }
    public record Post(@NotBlank @Size(max=200) String title,@NotBlank String category,
        @NotNull @Size(max=500) String summary,@NotBlank @Size(max=20000) String content,@NotBlank String status) {}
    private void validate(Authentication a,Post p) {
        manage(a);
        if(!Set.of("NEWS","ANNOUNCEMENT").contains(p.category())||!Set.of("DRAFT","PUBLISHED","ARCHIVED").contains(p.status()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid post type or status.");
    }
    @GetMapping("/posts")
    public List<Map<String,Object>> posts(Authentication a) {
        return jdbc.queryForList("SELECT p.*,u.name AS author_name,EXISTS(SELECT 1 FROM hr_post_images i WHERE i.post_id=p.id) AS has_image FROM hr_posts p JOIN users u ON u.id=p.created_by"+(HrController.payrollManager(a)?"":" WHERE p.status='PUBLISHED'")+" ORDER BY p.id DESC LIMIT 500");
    }
    @GetMapping("/posts/{id}")
    public Map<String,Object> post(Authentication a,@PathVariable long id) {
        var rows=jdbc.queryForList("SELECT p.*,u.name AS author_name,EXISTS(SELECT 1 FROM hr_post_images i WHERE i.post_id=p.id) AS has_image FROM hr_posts p JOIN users u ON u.id=p.created_by WHERE p.id=?"
            +(HrController.payrollManager(a)?"":" AND p.status='PUBLISHED'"),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    @PostMapping(value="/posts",consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> create(Authentication a,@Valid @RequestBody Post p) {
        validate(a,p);
        Long uid=jdbc.queryForObject("SELECT id FROM users WHERE lower(username)=lower(?)",Long.class,a.getName());
        Long id=jdbc.queryForObject("INSERT INTO hr_posts(title,category,summary,content,status,created_by,published_at) VALUES(?,?,?,?,?,?,CASE WHEN ?='PUBLISHED' THEN current_timestamp ELSE NULL END) RETURNING id",Long.class,p.title(),p.category(),p.summary(),p.content(),p.status(),uid,p.status());
        return Map.of("id",id);
    }
    @PutMapping(value="/posts/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) @Transactional
    public void edit(Authentication a,@PathVariable long id,@Valid @RequestBody Post p) {
        validate(a,p);
        if(jdbc.update("UPDATE hr_posts SET title=?,category=?,summary=?,content=?,status=?,updated_at=current_timestamp,published_at=CASE WHEN ?='PUBLISHED' THEN COALESCE(published_at,current_timestamp) ELSE published_at END WHERE id=?",p.title(),p.category(),p.summary(),p.content(),p.status(),p.status(),id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    @DeleteMapping("/posts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void delete(Authentication a,@PathVariable long id) {
        manage(a);
        if(jdbc.update("DELETE FROM hr_posts WHERE id=? AND status IN ('DRAFT','ARCHIVED')",id)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Archive a published post before deleting it.");
    }

    @PostMapping(value="/posts",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> createWithImage(Authentication a,@Valid @RequestPart("post") Post p,
            @RequestPart(value="image",required=false) MultipartFile image) {
        validate(a,p);
        var content=RecruitmentImage.read(image);
        var created=create(a,p);
        if(content!=null) saveImage(((Number)created.get("id")).longValue(),content);
        return created;
    }

    @PutMapping(value="/posts/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    public void editWithImage(Authentication a,@PathVariable long id,@Valid @RequestPart("post") Post p,
            @RequestPart(value="image",required=false) MultipartFile image,
            @RequestParam(defaultValue="false") boolean removeImage) {
        validate(a,p);
        if(removeImage&&image!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Chọn thay ảnh hoặc gỡ ảnh, không dùng cả hai.");
        var content=RecruitmentImage.read(image);
        edit(a,id,p);
        if(content!=null)saveImage(id,content);
        else if(removeImage)jdbc.update("DELETE FROM hr_post_images WHERE post_id=?",id);
    }

    private void saveImage(long id,RecruitmentImage.Content image) {
        jdbc.update("""
            INSERT INTO hr_post_images(post_id,content_type,image_data) VALUES(?,?,?)
            ON CONFLICT(post_id) DO UPDATE SET content_type=EXCLUDED.content_type,image_data=EXCLUDED.image_data
            """,id,image.contentType(),image.bytes());
    }

    @GetMapping("/posts/{id}/image")
    public ResponseEntity<byte[]> image(Authentication a,@PathVariable long id) {
        var rows=jdbc.queryForList("SELECT i.content_type,i.image_data FROM hr_post_images i JOIN hr_posts p ON p.id=i.post_id WHERE p.id=?"
            +(HrController.payrollManager(a)?"":" AND p.status='PUBLISHED'"),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var image=rows.getFirst();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)image.get("content_type")))
            .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff")
            .body((byte[])image.get("image_data"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(error.getReason(),"Không thể thực hiện thao tác.")));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,String>> tooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(Map.of("message","Ảnh không được vượt quá 5 MB."));
    }
}
