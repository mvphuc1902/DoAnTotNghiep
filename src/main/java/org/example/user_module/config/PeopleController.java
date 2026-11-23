package org.example.user_module.config;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/people")
public class PeopleController {
 private final JdbcTemplate db; public PeopleController(JdbcTemplate db){this.db=db;}
 public record Heartbeat(UUID tabId,String state){}
 static String sessionKey(String id){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private long user(Authentication a){return db.queryForObject("SELECT id FROM users WHERE lower(username)=lower(?)",Long.class,a.getName());}
 private boolean role(Authentication a,String... roles){return a.getAuthorities().stream().anyMatch(x->Arrays.asList(roles).contains(x.getAuthority()));}
 @PostMapping("/presence") @Transactional public void heartbeat(Authentication a,HttpSession session,@RequestBody Heartbeat input){
  if(input==null||input.tabId()==null||!Set.of("ACTIVE","AWAY").contains(Objects.toString(input.state(),"")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Trạng thái không hợp lệ.");
  db.update("INSERT INTO user_presence(user_id,session_key,tab_id,state,last_seen) VALUES(?,?,?,?,now()) ON CONFLICT(user_id,session_key,tab_id) DO UPDATE SET state=excluded.state,last_seen=now()",user(a),sessionKey(session.getId()),input.tabId(),input.state());
  db.update("DELETE FROM user_presence WHERE last_seen<now()-interval '1 day'");
 }
 @PostMapping("/presence/leave") public void leave(Authentication a,HttpSession session){db.update("DELETE FROM user_presence WHERE user_id=? AND session_key=?",user(a),sessionKey(session.getId()));}
 @GetMapping public List<Map<String,Object>> people(){return db.queryForList("""
 SELECT u.id::text AS id,coalesce(nullif(u.name,''),u.username) AS name,coalesce(nullif(u.department,''),(SELECT e.department FROM employees e WHERE e.user_id=u.id ORDER BY e.id LIMIT 1),'') AS department,
 EXISTS(SELECT 1 FROM user_avatars a WHERE a.user_id=u.id) AS has_avatar,
 coalesce((SELECT updated_at::text FROM user_avatars a WHERE a.user_id=u.id),'') AS avatar_version,
 CASE WHEN EXISTS(SELECT 1 FROM user_presence p WHERE p.user_id=u.id AND p.last_seen>now()-interval '2 minutes' AND p.state='ACTIVE') THEN 'ACTIVE'
 WHEN EXISTS(SELECT 1 FROM user_presence p WHERE p.user_id=u.id AND p.last_seen>now()-interval '2 minutes') THEN 'AWAY' ELSE 'OFFLINE' END AS presence
 FROM users u ORDER BY lower(coalesce(u.name,u.username))
 """);}
 @GetMapping("/{id}/avatar") public ResponseEntity<byte[]> getAvatar(@PathVariable long id){var rows=db.queryForList("SELECT image FROM user_avatars WHERE user_id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body((byte[])rows.getFirst().get("image"));}
 @PutMapping(value="/{id}/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional public void upload(Authentication a,@PathVariable long id,@RequestParam("image") MultipartFile file){
  boolean allowed=user(a)==id||role(a,"ROLE_ADMIN")||role(a,"ROLE_DIRECTOR","ROLE_HR")&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM employees WHERE user_id=?)",Boolean.class,id));
  if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền thay ảnh tài khoản này.");
  if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  db.update("INSERT INTO user_avatars(user_id,image,updated_at) VALUES(?,?,now()) ON CONFLICT(user_id) DO UPDATE SET image=excluded.image,updated_at=now()",id,readImage(file));
 }
 static byte[] readImage(MultipartFile file){
  if(file==null||file.isEmpty()||file.getSize()>2*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Chọn ảnh JPG/PNG không quá 2 MB.");
  try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes()))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException();var reader=readers.next();try{reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);int width=reader.getWidth(0),height=reader.getHeight(0);if(!Set.of("png","jpeg").contains(format)||width<=0||height<=0||width>4096||height>4096||(long)width*height>12_000_000)throw new IOException();var source=reader.read(0);var target=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);var g=target.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);int side=Math.min(width,height);g.drawImage(source,0,0,256,256,(width-side)/2,(height-side)/2,(width+side)/2,(height+side)/2,null);}finally{g.dispose();}var output=new ByteArrayOutputStream();ImageIO.write(target,"png",output);return output.toByteArray();}finally{reader.dispose();}}
  catch(IOException|IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ảnh không hợp lệ. Dùng JPG/PNG tối đa 4096 px mỗi chiều.");}
 }
}
