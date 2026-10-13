package com.textharbor;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("extraction")
public record ExtractionProperties(
        @NotNull Path storage,
        @Min(1) @Max(8) int workers,
        @Min(1) @Max(100) int queueCapacity,
        @Min(1) long maxUploadBytes,
        @Min(100) @Max(10_000_000) int maxChars,
        @Min(1) @Max(1000) int maxEmbedded,
        @Min(1) @Max(1000) int maxPages,
        @Min(5) @Max(600) int timeoutSeconds,
        @Min(1) int retentionMinutes,
        @Min(1) @Max(1000) int maxStoredJobs,
        String tesseractPath) {
}
