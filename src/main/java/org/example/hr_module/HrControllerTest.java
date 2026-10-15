package org.example.hr_module;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HrControllerTest {
    @Test void hrManagesPayrollWithoutOtherManagementRights() {
        assertTrue(HrController.payrollManager(auth("HR")));
        assertFalse(HrController.manages(auth("HR")));
        assertFalse(HrController.payrollManager(auth("EMPLOYEE")));
    }
    @Test void hrCanReadEveryoneLeavesButEmployeeOnlyReadsOwn() {
        controller.leaves(auth("HR"));
        verify(jdbc).queryForList(endsWith("ORDER BY l.id DESC LIMIT 500"));
        controller.leaves(staff);
        verify(jdbc).queryForList(contains("WHERE l.user_id=?"),eq(7L));
    }
    @Test void hrCanEditOwnPendingLeave() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",7L,"status","PENDING")));
        var input=new HrController.Leave(LocalDate.now(),LocalDate.now(),"ANNUAL","Updated reason");
        controller.editLeave(auth("HR"),1,input);
        verify(jdbc).update(startsWith("UPDATE hr_leave_requests SET start_date"),eq(input.startDate()),eq(input.endDate()),eq("ANNUAL"),eq("Updated reason"),eq(1L),eq(7L));
    }
    @Test void hrCannotEditOrReviewOrDeleteOthersLeave() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",8L,"status","PENDING")));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.editLeave(auth("HR"),1,new HrController.Leave(LocalDate.now(),LocalDate.now(),"ANNUAL","Test"))).getStatusCode().value());
        for(String status:List.of("APPROVED","REJECTED","CANCELLED"))
            assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.review(auth("HR"),1,new HrController.Review(status,""))).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->controller.deleteLeave(auth("HR"),1));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void reviewedLeaveCannotBeEditedEvenByOwner() {
        for(String status:List.of("APPROVED","REJECTED","CANCELLED")){
            when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",7L,"status",status)));
            assertThrows(IllegalArgumentException.class,()->controller.editLeave(auth("HR"),1,new HrController.Leave(LocalDate.now(),LocalDate.now(),"ANNUAL","Test")));
        }
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void leaveEditChecksOverlapExcludingItself() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",7L,"status","PENDING")));
        when(jdbc.queryForObject(contains("AND id<>?"),eq(Boolean.class),eq(7L),eq(1L),any(LocalDate.class),any(LocalDate.class))).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->controller.editLeave(auth("HR"),1,new HrController.Leave(LocalDate.now(),LocalDate.now(),"ANNUAL","Test")));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    JdbcTemplate jdbc; HrController controller;
    Authentication staff=auth("EMPLOYEE"), admin=auth("ADMIN");
    static Authentication auth(String role) { return new UsernamePasswordAuthenticationToken("test", "", List.of(new SimpleGrantedAuthority("ROLE_"+role))); }
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);controller=new HrController(jdbc);
        when(jdbc.queryForObject("SELECT id FROM users WHERE lower(username)=lower(?)",Long.class,"test")).thenReturn(7L);
    }
    @Test void onlyAdminAndDirectorManageHr() {
        assertTrue(HrController.manages(admin));assertTrue(HrController.manages(auth("DIRECTOR")));
        for(String role:List.of("EMPLOYEE","MANAGER","LEADER","VIEW_DASHBOARD"))assertFalse(HrController.manages(auth(role)));
    }
    @Test void payrollReadIsScopedToSignedInAccount() {
        controller.payroll(staff);
        verify(jdbc).queryForList(contains("WHERE p.user_id=?"),eq(7L));
    }
    @Test void taskReadIsScopedToAssigneeOrCreator() {
        controller.tasks(staff);
        verify(jdbc).queryForList(contains("WHERE t.assigned_to=?"),eq(7L));
    }
    @Test void staffCannotCreateOrDeletePayroll() {
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.createPay(staff,null)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.deletePay(staff,1)).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
    @Test void staffCannotAssignTasksToOthers() {
        var task=new HrController.Task("Test","",8L,LocalDate.now(),"NORMAL","TODO",0,"");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.task(staff,task)).getStatusCode().value());
    }
    @Test void rejectsReversedLeaveDates() {
        assertThrows(IllegalArgumentException.class,()->controller.leave(staff,new HrController.Leave(LocalDate.of(2026,10,3),LocalDate.of(2026,10,1),"ANNUAL","Test")));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsOverlappingLeave() {
        when(jdbc.queryForObject(startsWith("SELECT EXISTS(SELECT 1 FROM hr_leave_requests"),eq(Boolean.class),eq(7L),any(LocalDate.class),any(LocalDate.class))).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->controller.leave(staff,new HrController.Leave(LocalDate.now(),LocalDate.now(),"ANNUAL","Test")));
    }
    @Test void staffCannotApproveLeaveOrCancelSomeoneElses() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",8L,"status","PENDING")));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.review(staff,1,new HrController.Review("APPROVED",""))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.review(staff,1,new HrController.Review("CANCELLED",""))).getStatusCode().value());
    }
    @Test void preventsSelfApprovalAndRepeatedReview() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",7L,"status","PENDING")));
        assertThrows(IllegalArgumentException.class,()->controller.review(admin,1,new HrController.Review("APPROVED","")));
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",8L,"status","APPROVED")));
        assertThrows(IllegalArgumentException.class,()->controller.review(admin,1,new HrController.Review("REJECTED","")));
    }
    @Test void adminCanApproveAnotherPersonsPendingRequest() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",8L,"status","PENDING")));
        controller.review(admin,1,new HrController.Review("APPROVED","Approved for planned leave"));
        verify(jdbc).update(startsWith("UPDATE hr_leave_requests"),eq("APPROVED"),eq("Approved for planned leave"),eq(7L),eq(1L));
    }
    @Test void approvedLeaveCannotBeDeleted() {
        when(jdbc.queryForList("SELECT * FROM hr_leave_requests WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("user_id",8L,"status","APPROVED")));
        assertThrows(IllegalArgumentException.class,()->controller.deleteLeave(admin,1));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.deleteLeave(staff,1)).getStatusCode().value());
    }
    @Test void managerAssignmentRequiresSameDepartmentLeader() {
        var task=new HrController.Task("Test","",8L,LocalDate.now(),"NORMAL","TODO",0,"");
        assertThrows(ResponseStatusException.class,()->controller.task(auth("MANAGER"),task));
        verify(jdbc).queryForObject(contains("u.role='LEADER'"),eq(Boolean.class),eq(8L),eq(7L));
    }
    @Test void leaderAssignmentRequiresSameTeamEmployee() {
        var task=new HrController.Task("Test","",8L,LocalDate.now(),"NORMAL","TODO",0,"");
        assertThrows(ResponseStatusException.class,()->controller.task(auth("LEADER"),task));
        verify(jdbc).queryForObject(contains("lower(trim(u.team))"),eq(Boolean.class),eq(8L),eq(7L),eq(7L));
    }
    @Test void employeeCanUpdateProgressDeadlineAndWorkNote() {
        when(jdbc.queryForList("SELECT * FROM hr_tasks WHERE id=? FOR UPDATE",1L)).thenReturn(List.of(Map.of("assigned_to",7L,"created_by",8L,"title","Task","description","Assigned work","priority","NORMAL","status","TODO")));
        when(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,7L)).thenReturn(true);
        var input=new HrController.Task("Task","Assigned work",7L,LocalDate.of(2026,12,10),"NORMAL","IN_PROGRESS",60,"Completed API work");
        controller.editTask(staff,1,input);
        verify(jdbc).update(startsWith("UPDATE hr_tasks SET"),eq("Task"),eq("Assigned work"),eq(7L),eq(LocalDate.of(2026,12,10)),eq("NORMAL"),eq("IN_PROGRESS"),eq(60),eq("Completed API work"),eq(1L));
    }
    @Test void employeeCannotCreateEvenPersonalTask() {
        var task=new HrController.Task("Test","",7L,LocalDate.now(),"NORMAL","TODO",0,"");
        assertThrows(ResponseStatusException.class,()->controller.task(staff,task));
    }
}

