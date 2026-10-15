package org.example.hr_module;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecruitmentImageUploadTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private MockMvc mvc;
    private MockMultipartFile post() {
        return new MockMultipartFile("post","","application/json",("""
            {"title":"Engineer","department":"Engineering","location":"HCM","employmentType":"FULL_TIME",
             "description":"Build software","requirements":"Java","contactEmail":"hr@example.test",
             "deadline":"%s","status":"PUBLISHED"}
            """).formatted(java.time.LocalDate.now().plusDays(10)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private MockMultipartFile image() throws Exception {
        return new MockMultipartFile("image","photo.png","image/png",RecruitmentImageTest.picture("png",12,8));
    }
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new RecruitmentController(jdbc)).build();
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(42L);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
    }
    @Test void hrCanCreatePostAndImageTogether() throws Exception {
        mvc.perform(multipart("/api/hr/recruitment").file(post()).file(image()).principal(HrControllerTest.auth("HR")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(42));
        verify(jdbc).update(contains("INSERT INTO hr_job_post_images"),eq(42L),eq("image/png"),any(byte[].class));
    }
    @Test void invalidImageDoesNotCreatePost() throws Exception {
        clearInvocations(jdbc);
        mvc.perform(multipart("/api/hr/recruitment").file(post())
            .file(new MockMultipartFile("image","bad.png","image/png",new byte[]{1,2,3}))
            .principal(HrControllerTest.auth("HR"))).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }
    @Test void employeeCannotUpload() throws Exception {
        mvc.perform(multipart("/api/hr/recruitment").file(post()).file(image()).principal(HrControllerTest.auth("EMPLOYEE")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }
    @Test void updatingTextKeepsExistingImage() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,"/api/hr/recruitment/42").file(post()).principal(HrControllerTest.auth("HR")))
            .andExpect(status().isOk());
        verify(jdbc,never()).update(contains("hr_job_post_images"),any(Object[].class));
    }
    @Test void hrCanReplaceImage() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,"/api/hr/recruitment/42").file(post()).file(image()).principal(HrControllerTest.auth("HR")))
            .andExpect(status().isOk());
        verify(jdbc).update(contains("ON CONFLICT(post_id)"),eq(42L),eq("image/png"),any(byte[].class));
    }
    @Test void hrCanRemoveImage() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,"/api/hr/recruitment/42").file(post()).param("removeImage","true")
            .principal(HrControllerTest.auth("HR"))).andExpect(status().isOk());
        verify(jdbc).update("DELETE FROM hr_job_post_images WHERE post_id=?",42L);
    }
    @Test void invalidPostDoesNotSaveImage() throws Exception {
        mvc.perform(multipart("/api/hr/recruitment").file(new MockMultipartFile("post","","application/json","{}".getBytes()))
            .file(image()).principal(HrControllerTest.auth("HR"))).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }
    @Test void missingPostCannotGetAnImage() throws Exception {
        when(jdbc.update(startsWith("UPDATE hr_job_posts"),any(Object[].class))).thenReturn(0);
        mvc.perform(multipart(HttpMethod.PUT,"/api/hr/recruitment/42").file(post()).file(image()).principal(HrControllerTest.auth("HR")))
            .andExpect(status().isNotFound());
        verify(jdbc,never()).update(contains("hr_job_post_images"),any(Object[].class));
    }
    @Test void employeeOnlySeesPublishedUnexpiredImages() throws Exception {
        mvc.perform(get("/api/hr/recruitment/42/image").principal(HrControllerTest.auth("EMPLOYEE")))
            .andExpect(status().isNotFound());
        verify(jdbc).queryForList(contains("AND p.status='PUBLISHED' AND p.deadline>=CURRENT_DATE"),eq(42L));
    }
    @Test void imageResponseIsAnImageAndNotPubliclyCached() throws Exception {
        byte[] bytes=RecruitmentImageTest.picture("png",12,8);
        when(jdbc.queryForList(anyString(),eq(42L))).thenReturn(List.of(Map.of("content_type","image/png","image_data",bytes)));
        mvc.perform(get("/api/hr/recruitment/42/image").principal(HrControllerTest.auth("HR")))
            .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control","private, no-store")).andExpect(content().bytes(bytes));
    }
}
