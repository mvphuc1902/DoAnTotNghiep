package org.example.hr_module;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class HrNotificationTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final HrNotificationController controller=new HrNotificationController(jdbc);

    private void currentUser() {
        when(jdbc.queryForList(startsWith("SELECT id FROM users"),eq(Long.class),anyString())).thenReturn(List.of(12L));
    }
    @Test void feedCountsOnlyVisibleUnreadNotificationsForCurrentUser() {
        currentUser();
        when(jdbc.queryForList(contains("LIMIT 50"),eq(12L))).thenReturn(List.of(Map.of("id","7","is_read",false)));
        when(jdbc.queryForObject(contains("SELECT count(*)"),eq(Long.class),eq(12L))).thenReturn(1L);
        var response=controller.list(HrControllerTest.auth("EMPLOYEE"));
        assertEquals(1L,response.get("unreadCount"));
        verify(jdbc).queryForList(contains("j.deadline>=CURRENT_DATE"),eq(12L));
        verify(jdbc).queryForObject(contains("p.status='PUBLISHED'"),eq(Long.class),eq(12L));
    }
    @Test void readingUsesAuthenticatedUserAndIsIdempotent() {
        currentUser();controller.read(HrControllerTest.auth("EMPLOYEE"),7L);
        verify(jdbc).update(contains("ON CONFLICT DO NOTHING"),eq(12L),eq(7L));
    }
    @Test void markAllDoesNotReadNotificationsNewerThanVisibleSnapshot() {
        currentUser();controller.readAll(HrControllerTest.auth("HR"),new HrNotificationController.ReadThrough(20));
        verify(jdbc).update(contains("WHERE n.id<=?"),eq(12L),eq(20L));
    }
    @Test void missingAccountCannotReadNotificationData() {
        assertThrows(ResponseStatusException.class,()->controller.list(HrControllerTest.auth("EMPLOYEE")));
        verify(jdbc,never()).queryForObject(anyString(),eq(Long.class),any(Object[].class));
    }
    @Test void employeeCannotOpenHiddenNewsThroughNotificationLink() {
        assertThrows(ResponseStatusException.class,()->new HrWorkspaceController(jdbc).post(HrControllerTest.auth("EMPLOYEE"),8));
        verify(jdbc).queryForList(contains("AND p.status='PUBLISHED'"),eq(8L));
    }
    @Test void employeeCannotOpenExpiredRecruitmentThroughNotificationLink() {
        assertThrows(ResponseStatusException.class,()->new RecruitmentController(jdbc).detail(HrControllerTest.auth("EMPLOYEE"),8));
        verify(jdbc).queryForList(contains("AND p.deadline>=CURRENT_DATE"),eq(8L));
    }
}
