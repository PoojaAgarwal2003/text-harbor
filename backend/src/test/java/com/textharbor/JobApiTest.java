package com.textharbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.textharbor.Models.CreatedJob;
import com.textharbor.Models.ExtractionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class JobApiTest {
    private static final Path STORAGE;
    static {
        try {
            STORAGE = Files.createTempDirectory("text-harbor-api-tests-");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("extraction.storage", STORAGE::toString);
        registry.add("extraction.max-upload-bytes", () -> 1024);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ForkedWorker worker;

    @Test
    void completesJobWithTokenProtectedResultAndDeletion() throws Exception {
        when(worker.execute(anyString(), any(), any()))
                .thenReturn(new ExtractionResult("Extracted text", "text/plain", List.of(), null, null));
        String response = mvc.perform(multipart("/api/jobs")
                        .file(new MockMultipartFile("file", "../../notes.txt", "text/plain", "Source".getBytes()))
                        .param("ocrMode", "NEVER"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.job.filename").value("notes.txt"))
                .andReturn().getResponse().getContentAsString();
        CreatedJob created = mapper.readValue(response, CreatedJob.class);
        String path = "/api/jobs/" + created.job().id();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("X-Job-Token", "wrong")).andExpect(status().isNotFound());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get(path).header("X-Job-Token", created.accessToken()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                        .andExpect(jsonPath("$.tokenHash").doesNotExist()));
        mvc.perform(get(path + "/result").header("X-Job-Token", created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Extracted text"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path + "/download").header("X-Job-Token", created.accessToken()))
                .andExpect(content().string("Extracted text"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(Files.exists(STORAGE.resolve(created.job().id()).resolve("source"))).isFalse());
        mvc.perform(delete(path).header("X-Job-Token", created.accessToken())).andExpect(status().isNoContent());
        mvc.perform(get(path).header("X-Job-Token", created.accessToken())).andExpect(status().isNotFound());
        assertThat(Files.exists(STORAGE.resolve(created.job().id()))).isFalse();
    }

    @Test
    void rejectsEmptyOversizedAndInvalidOptions() throws Exception {
        mvc.perform(multipart("/api/jobs").file(new MockMultipartFile("file", new byte[0])))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));
        mvc.perform(multipart("/api/jobs").file(new MockMultipartFile("file", new byte[1025])))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/jobs").file(new MockMultipartFile("file", "hello".getBytes()))
                        .param("ocrMode", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/jobs").file(new MockMultipartFile("file", "hello".getBytes()))
                        .param("language", "--attack"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LANGUAGE"));
    }

    @Test
    void publishesParserWarningsAsPartialNotCompleted() throws Exception {
        when(worker.execute(anyString(), any(), any()))
                .thenReturn(new ExtractionResult("Partial", "text/plain", List.of("Text limit reached"), null, null));
        CreatedJob created = mapper.readValue(mvc.perform(multipart("/api/jobs")
                        .file(new MockMultipartFile("file", "long.txt", "text/plain", "Source".getBytes()))
                        .param("ocrMode", "NEVER")).andReturn().getResponse().getContentAsString(), CreatedJob.class);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/jobs/" + created.job().id()).header("X-Job-Token", created.accessToken()))
                        .andExpect(jsonPath("$.status").value("PARTIAL"))
                        .andExpect(jsonPath("$.warnings[0]").value("Text limit reached")));
    }
}
