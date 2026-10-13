package com.textharbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.textharbor.Models.ExtractionResult;
import com.textharbor.Models.OcrMode;
import com.textharbor.Models.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class JobServiceTest {
    @TempDir Path storage;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OcrSupport ocr = mock(OcrSupport.class);
    private final ForkedWorker worker = mock(ForkedWorker.class);
    private final ExtractionResult text = new ExtractionResult("Local text", "text/plain", List.of(), null, null);

    private JobService service() throws Exception {
        when(ocr.languages()).thenReturn(List.of());
        return new JobService(new ExtractionProperties(storage, 1, 1, 1024,
                1000, 10, 10, 10, 60, 100, ""), mapper, ocr, worker);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "notes.txt", "text/plain", "Hello".getBytes());
    }

    @Test
    void limitsQueueAndCancelsQueuedWorkWithoutRetainingTheUpload() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.execute(anyString(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return text;
        });
        JobService service = service();
        try {
            service.submit(file(), "eng", OcrMode.NEVER);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = service.submit(file(), "eng", OcrMode.NEVER);
            assertThatThrownBy(() -> service.submit(file(), "eng", OcrMode.NEVER))
                    .isInstanceOf(ApiException.class).hasMessageContaining("Workers are busy");
            service.cancel(queued.job().id(), queued.accessToken());
            assertThat(service.authorized(queued.job().id(), queued.accessToken()).status()).isEqualTo(Status.CANCELLED);
            assertThat(storage.resolve(queued.job().id()).resolve("source")).doesNotExist();
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void activeCancellationCannotPublishSuccessfulText() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(worker.execute(anyString(), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier cancelled = invocation.getArgument(2);
            entered.countDown();
            await().atMost(Duration.ofSeconds(5)).until(cancelled::getAsBoolean);
            return text;
        });
        JobService service = service();
        try {
            var created = service.submit(file(), "eng", OcrMode.NEVER);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            service.cancel(created.job().id(), created.accessToken());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(storage.resolve(created.job().id()).resolve("source")).doesNotExist());
            assertThat(service.authorized(created.job().id(), created.accessToken()).status()).isEqualTo(Status.CANCELLED);
            assertThatThrownBy(() -> service.result(created.job().id(), created.accessToken())).isInstanceOf(ApiException.class);
            assertThat(storage.resolve(created.job().id()).resolve("result.json")).doesNotExist();
        } finally {
            service.close();
        }
    }

    @Test
    void persistsCompletedJobsAcrossRestartWithoutPlaintextAccessTokens() throws Exception {
        when(worker.execute(anyString(), any(), any())).thenReturn(text);
        JobService first = service();
        var created = first.submit(file(), "eng", OcrMode.NEVER);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(first.authorized(created.job().id(), created.accessToken()).status()).isEqualTo(Status.COMPLETED));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(storage.resolve(created.job().id()).resolve("source")).doesNotExist());
        first.close();
        assertThat(Files.readString(storage.resolve(created.job().id()).resolve("job.json")))
                .doesNotContain(created.accessToken());
        JobService restarted = service();
        try {
            assertThat(restarted.result(created.job().id(), created.accessToken()).text()).isEqualTo("Local text");
        } finally {
            restarted.close();
        }
    }
}
