package org.example.hr_module;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/hr/notifications")
public class HrNotificationController {
    private final JdbcTemplate jdbc;
    public HrNotificationController(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    private static final String SOURCES = """
        FROM hr_notifications n
        LEFT JOIN hr_posts p ON p.id=n.post_id
        LEFT JOIN hr_job_posts j ON j.id=n.job_post_id
        """;
    private static final String VISIBLE = """
        ((p.id IS NOT NULL AND p.status='PUBLISHED')
         OR (j.id IS NOT NULL AND j.status='PUBLISHED' AND j.deadline>=CURRENT_DATE))
        """;

    private long userId(Authentication auth) {
        var ids=jdbc.queryForList("SELECT id FROM users WHERE lower(username)=lower(?)",Long.class,auth.getName());
        if(ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ids.getFirst();
    }

    @GetMapping @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> list(Authentication auth) {
        long uid=userId(auth);
        var rows=jdbc.queryForList("""
            SELECT n.id::text AS id,coalesce(n.post_id,n.job_post_id)::text AS source_id,
                CASE WHEN n.job_post_id IS NOT NULL THEN 'RECRUITMENT' ELSE 'POST' END AS kind,
                coalesce(p.title,j.title) AS title,n.created_at,r.read_at IS NOT NULL AS is_read
            """+SOURCES+" LEFT JOIN hr_notification_reads r ON r.notification_id=n.id AND r.user_id=? WHERE "+VISIBLE
                +" ORDER BY (r.read_at IS NULL) DESC,n.id DESC LIMIT 50",uid);
        var count=jdbc.queryForObject("SELECT count(*) "+SOURCES+" WHERE "+VISIBLE
            +" AND NOT EXISTS (SELECT 1 FROM hr_notification_reads r WHERE r.notification_id=n.id AND r.user_id=?)",Long.class,uid);
        return Map.of("notifications",rows,"unreadCount",count);
    }

    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void read(Authentication auth,@PathVariable long id) {
        long uid=userId(auth);
        jdbc.update("INSERT INTO hr_notification_reads(notification_id,user_id) SELECT n.id,? "+SOURCES
            +" WHERE n.id=? AND "+VISIBLE+" ON CONFLICT DO NOTHING",uid,id);
    }

    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void readAll(Authentication auth,@RequestBody ReadThrough input) {
        if(input.throughId()<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long uid=userId(auth);
        // Do not silently mark newer notifications that arrived after the user opened the list.
        jdbc.update("INSERT INTO hr_notification_reads(notification_id,user_id) SELECT n.id,? "+SOURCES
            +" WHERE n.id<=? AND "+VISIBLE+" ON CONFLICT DO NOTHING",uid,input.throughId());
    }
    public record ReadThrough(long throughId) {}
}
