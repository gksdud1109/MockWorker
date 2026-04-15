package com.realteeth.mockworker.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.service.IdempotencyConflictException;
import com.realteeth.mockworker.service.ImageJobService;
import com.realteeth.mockworker.service.JobNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ImageJobService service;

    @Test
    void post_without_body_returns_400() throws Exception {
        mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void post_with_blank_image_url_returns_400() throws Exception {
        mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void post_without_idempotency_key_succeeds_with_auto_key() throws Exception {
        when(service.accept(anyString(), anyString()))
                .thenReturn(stubJob("job-1"));

        mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://img/a.png\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void post_with_idempotency_conflict_returns_409() throws Exception {
        when(service.accept(anyString(), anyString()))
                .thenThrow(new IdempotencyConflictException("key reused with different payload"));

        mvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "my-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://img/a.png\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"));
    }

    @Test
    void get_unknown_job_returns_404() throws Exception {
        when(service.get("no-such-id")).thenThrow(new JobNotFoundException("no-such-id"));

        mvc.perform(get("/api/v1/jobs/no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    private ImageJob stubJob(String id) {
        return ImageJob.accept(id, "https://img/a.png", "fp", Instant.now());
    }
}
