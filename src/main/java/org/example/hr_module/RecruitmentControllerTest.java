package org.example.hr_module;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class RecruitmentControllerTest {
    @Test void staffOnlyReadsPublishedUnexpiredPosts() {
        var jdbc=mock(JdbcTemplate.class);new RecruitmentController(jdbc).list(HrControllerTest.auth("EMPLOYEE"));
        verify(jdbc).queryForList(contains("WHERE status='PUBLISHED' AND deadline>=CURRENT_DATE"));
    }
    @Test void employeeCannotPublish() {
        var jdbc=mock(JdbcTemplate.class);var controller=new RecruitmentController(jdbc);
        assertThrows(ResponseStatusException.class,()->controller.create(HrControllerTest.auth("EMPLOYEE"),post(LocalDate.now().plusDays(2))));verifyNoInteractions(jdbc);
    }
    @Test void expiredPostCannotBePublished() {
        var jdbc=mock(JdbcTemplate.class);var controller=new RecruitmentController(jdbc);
        assertThrows(ResponseStatusException.class,()->controller.create(HrControllerTest.auth("HR"),post(LocalDate.now().minusDays(1))));verifyNoInteractions(jdbc);
    }
    static RecruitmentController.Post post(LocalDate deadline) {return new RecruitmentController.Post("Engineer","Engineering","HCM","FULL_TIME","Build software","Java","hr@example.test",deadline,"PUBLISHED");}
}
