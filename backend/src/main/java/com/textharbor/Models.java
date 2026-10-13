package com.textharbor;

import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {}

    public enum Status {
        QUEUED, PROCESSING, COMPLETED, PARTIAL, FAILED, CANCELLED;

        public boolean terminal() {
            return this != QUEUED && this != PROCESSING;
        }
    }

    public enum OcrMode { AUTO, ALWAYS, NEVER }

    public record WorkerRequest(String filename, String language, OcrMode ocrMode,
            int maxChars, int maxEmbedded, int maxPages, boolean ocrAvailable) {}

    public record ExtractionResult(String text, String mediaType, List<String> warnings,
            String errorCode, String errorMessage) {
        public static ExtractionResult failure(String code, String message) {
            return new ExtractionResult("", "application/octet-stream", List.of(), code, message);
        }
    }

    public record Job(String id, String tokenHash, String filename, long size,
            Status status, Instant createdAt, Instant updatedAt, String language,
            OcrMode ocrMode, String mediaType, int characters, List<String> warnings,
            String errorCode, String errorMessage) {
        public Job transition(Status next, ExtractionResult result) {
            return new Job(id, tokenHash, filename, size, next, createdAt, Instant.now(),
                    language, ocrMode, result == null ? mediaType : result.mediaType(),
                    result == null ? characters : result.text().length(),
                    result == null ? warnings : result.warnings(),
                    result == null ? errorCode : result.errorCode(),
                    result == null ? errorMessage : result.errorMessage());
        }

        public JobView view() {
            return new JobView(id, filename, size, status, createdAt, updatedAt,
                    language, ocrMode, mediaType, characters, warnings, errorCode, errorMessage);
        }
    }

    public record JobView(String id, String filename, long size, Status status,
            Instant createdAt, Instant updatedAt, String language, OcrMode ocrMode,
            String mediaType, int characters, List<String> warnings,
            String errorCode, String errorMessage) {}

    public record CreatedJob(JobView job, String accessToken) {}

    public record Capabilities(long maxUploadBytes, int maxPages, int maxCharacters,
            int retentionMinutes, boolean ocrAvailable, List<String> languages,
            List<String> formats) {}
}
