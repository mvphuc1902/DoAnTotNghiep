package org.example.hr_module;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestController @RequestMapping("/api/hr/recruitment")
public class RecruitmentController {
    private final JdbcTemplate jdbc;
    public RecruitmentController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Post(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=255) String department,
        @NotBlank @Size(max=255) String location,@NotBlank String employmentType,
        @NotBlank @Size(max=8000) String description,@NotBlank @Size(max=8000) String requirements,
        @NotBlank @Email @Size(max=255) String contactEmail,@NotNull LocalDate deadline,@NotBlank String status) {}
    private void check(Authentication a,Post p) {
        if(!HrController.payrollManager(a))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!Set.of("DRAFT","PUBLISHED","CLOSED").contains(p.status())||!Set.of("FULL_TIME","PART_TIME","CONTRACT","INTERNSHIP").contains(p.employmentType()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recruitment option.");
        if(p.status().equals("PUBLISHED")&&p.deadline().isBefore(LocalDate.now()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a future application deadline.");
    }
    @GetMapping public List<Map<String,Object>> list(Authentication a) {
        return jdbc.queryForList("SELECT p.*, EXISTS(SELECT 1 FROM hr_job_post_images i WHERE i.post_id=p.id) AS has_image FROM hr_job_posts p"+(HrController.payrollManager(a)?"":" WHERE status='PUBLISHED' AND deadline>=CURRENT_DATE")+" ORDER BY id DESC LIMIT 500");
    }
    @GetMapping("/{id}")
    public Map<String,Object> detail(Authentication a,@PathVariable long id) {
        var rows=jdbc.queryForList("SELECT p.*, EXISTS(SELECT 1 FROM hr_job_post_images i WHERE i.post_id=p.id) AS has_image FROM hr_job_posts p WHERE p.id=?"
            +(HrController.payrollManager(a)?"":" AND p.status='PUBLISHED' AND p.deadline>=CURRENT_DATE"),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> create(Authentication a,@Valid @RequestBody Post p) {
        check(a,p);
        var uid=jdbc.queryForObject("SELECT id FROM users WHERE lower(username)=lower(?)",Long.class,a.getName());
        return Map.of("id",jdbc.queryForObject("INSERT INTO hr_job_posts(title,department,location,employment_type,description,requirements,contact_email,deadline,status,created_by) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,p.title(),p.department(),p.location(),p.employmentType(),p.description(),p.requirements(),p.contactEmail(),p.deadline(),p.status(),uid));
    }
    @PutMapping(value="/{id}", consumes=MediaType.APPLICATION_JSON_VALUE) @Transactional
    public void edit(Authentication a,@PathVariable long id,@Valid @RequestBody Post p) {
        check(a,p);
        if(jdbc.update("UPDATE hr_job_posts SET title=?,department=?,location=?,employment_type=?,description=?,requirements=?,contact_email=?,deadline=?,status=?,updated_at=current_timestamp WHERE id=?",p.title(),p.department(),p.location(),p.employmentType(),p.description(),p.requirements(),p.contactEmail(),p.deadline(),p.status(),id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void delete(Authentication a,@PathVariable long id) {
        if(!HrController.payrollManager(a))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(jdbc.update("DELETE FROM hr_job_posts WHERE id=? AND status IN ('DRAFT','CLOSED')",id)==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Close a published post before deleting it.");
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> createWithImage(Authentication a, @Valid @RequestPart("post") Post p,
            @RequestPart(value="image",required=false) MultipartFile image) {
        check(a,p);
        var content=RecruitmentImage.read(image);
        var created=create(a,p);
        if(content!=null) saveImage(((Number)created.get("id")).longValue(),content);
        return created;
    }

    @PutMapping(value="/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    public void editWithImage(Authentication a,@PathVariable long id,@Valid @RequestPart("post") Post p,
            @RequestPart(value="image",required=false) MultipartFile image,
            @RequestParam(defaultValue="false") boolean removeImage) {
        check(a,p);
        if(removeImage && image!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Chọn thay ảnh hoặc gỡ ảnh, không dùng cả hai.");
        var content=RecruitmentImage.read(image);
        edit(a,id,p);
        if(content!=null) saveImage(id,content);
        else if(removeImage) jdbc.update("DELETE FROM hr_job_post_images WHERE post_id=?",id);
    }

    private void saveImage(long id,RecruitmentImage.Content image) {
        jdbc.update("""
            INSERT INTO hr_job_post_images(post_id,content_type,image_data) VALUES(?,?,?)
            ON CONFLICT(post_id) DO UPDATE SET content_type=EXCLUDED.content_type,image_data=EXCLUDED.image_data
            """,id,image.contentType(),image.bytes());
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(Authentication a,@PathVariable long id) {
        var rows=jdbc.queryForList("SELECT i.content_type,i.image_data FROM hr_job_post_images i JOIN hr_job_posts p ON p.id=i.post_id WHERE p.id=?"
            +(HrController.payrollManager(a)?"":" AND p.status='PUBLISHED' AND p.deadline>=CURRENT_DATE"),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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
