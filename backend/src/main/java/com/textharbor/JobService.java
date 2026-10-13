package com.textharbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.textharbor.Models.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class JobService {
    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExtractionProperties properties;
    private final ObjectMapper mapper;
    private final OcrSupport ocr;
    private final ForkedWorker worker;
    private final ThreadPoolExecutor executor;
    private final Path storage;

    public JobService(ExtractionProperties properties, ObjectMapper mapper,
            OcrSupport ocr, ForkedWorker worker) throws IOException {
        this.properties = properties;
        this.mapper = mapper;
        this.ocr = ocr;
        this.worker = worker;
        storage = properties.storage().toAbsolutePath().normalize();
        Files.createDirectories(storage);
        if (Files.getFileStore(storage).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(storage, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        executor = new ThreadPoolExecutor(properties.workers(), properties.workers(),
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(properties.queueCapacity()),
                Thread.ofPlatform().name("extraction-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        restore();
    }

    public synchronized CreatedJob submit(MultipartFile file, String language, OcrMode mode) throws IOException {
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Choose a non-empty file.");
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds the upload limit.");
        }
        if (!language.matches("[a-zA-Z][a-zA-Z0-9_/-]{1,60}(\\+[a-zA-Z][a-zA-Z0-9_/-]{1,60}){0,3}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LANGUAGE", "Invalid OCR language selection.");
        }
        if (mode != OcrMode.NEVER && ocr.available()
                && !ocr.languages().containsAll(List.of(language.split("\\+")))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LANGUAGE_UNAVAILABLE", "Selected OCR language data is not installed.");
        }
        if (mode == OcrMode.ALWAYS && !ocr.available()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OCR_UNAVAILABLE", "Whole-page OCR requires local Tesseract.");
        }
        if (jobs.size() >= properties.maxStoredJobs()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "STORAGE_FULL", "Job storage is full. Delete old jobs or wait for expiry.");
        }
        String id = UUID.randomUUID().toString();
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String filename = safeFilename(file.getOriginalFilename());
        Path directory = directory(id);
        Files.createDirectory(directory);
        Job job = new Job(id, hash(token), filename, file.getSize(), Status.QUEUED,
                Instant.now(), Instant.now(), language, mode, null, 0, List.of(), null, null);
        try {
            try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(directory.resolve("source"))) {
                long copied = input.transferTo(output);
                if (copied != file.getSize() || copied > properties.maxUploadBytes()) {
                    throw new IOException("Upload size did not match the declared size");
                }
            }
            mapper.writeValue(directory.resolve("request.json").toFile(),
                    new WorkerRequest(filename, language, mode, properties.maxChars(),
                            properties.maxEmbedded(), properties.maxPages(), ocr.available()));
            save(job);
            executor.execute(() -> process(id));
            return new CreatedJob(job.view(), token);
        } catch (RejectedExecutionException e) {
            jobs.remove(id);
            deleteTree(directory);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUEUE_FULL", "Workers are busy. Retry shortly.");
        } catch (IOException | RuntimeException e) {
            jobs.remove(id);
            deleteTree(directory);
            throw e;
        }
    }

    public synchronized Job authorized(String id, String token) {
        Job job = jobs.get(id);
        if (job == null || token == null || token.length() > 128
                || !MessageDigest.isEqual(job.tokenHash().getBytes(StandardCharsets.US_ASCII),
                        hash(token).getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Job not found or access token is invalid.");
        }
        return job;
    }

    public synchronized ExtractionResult result(String id, String token) throws IOException {
        Job job = authorized(id, token);
        if (job.status() != Status.COMPLETED && job.status() != Status.PARTIAL) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_NOT_READY", "This job has no available text result.");
        }
        return mapper.readValue(directory(id).resolve("result.json").toFile(), ExtractionResult.class);
    }

    public synchronized JobView cancel(String id, String token) throws IOException {
        Job job = authorized(id, token);
        if (!job.status().terminal()) {
            job = job.transition(Status.CANCELLED, null);
            save(job);
            worker.cancel(id);
            if (!active.contains(id)) Files.deleteIfExists(directory(id).resolve("source"));
        }
        return job.view();
    }

    public synchronized void delete(String id, String token) throws IOException {
        Job job = authorized(id, token);
        if (!job.status().terminal()) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_ACTIVE", "Cancel the job and wait for it to stop before deleting.");
        }
        if (active.contains(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_STOPPING", "The worker is stopping. Retry deletion shortly.");
        }
        deleteTree(directory(id));
        jobs.remove(id);
    }

    private final java.util.Set<String> active = ConcurrentHashMap.newKeySet();

    private void process(String id) {
        synchronized (this) {
            Job job = jobs.get(id);
            if (job == null || job.status() != Status.QUEUED) return;
            active.add(id);
            try {
                save(job.transition(Status.PROCESSING, null));
            } catch (IOException e) {
                active.remove(id);
                LOG.error("Cannot persist processing state for job {}", id, e);
                jobs.put(id, job.transition(Status.FAILED,
                        ExtractionResult.failure("STORAGE_ERROR", "Cannot persist job state.")));
                return;
            }
        }
        try {
            ExtractionResult result = worker.execute(id, directory(id),
                    () -> !jobs.containsKey(id) || jobs.get(id).status() != Status.PROCESSING);
            synchronized (this) {
                Job current = jobs.get(id);
                if (current != null && current.status() == Status.PROCESSING) {
                    Status next = result.errorCode() != null ? Status.FAILED
                            : result.warnings().isEmpty() ? Status.COMPLETED : Status.PARTIAL;
                    writeJson(directory(id).resolve("result.json"), result);
                    save(current.transition(next, result));
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.error("Worker execution failed for job {}: {}", id, e.getClass().getSimpleName());
            synchronized (this) {
                Job current = jobs.get(id);
                if (current != null && current.status() == Status.PROCESSING) {
                    Job failed = current.transition(Status.FAILED,
                            ExtractionResult.failure("WORKER_ERROR", "The extraction worker could not finish."));
                    try {
                        save(failed);
                    } catch (IOException storageError) {
                        jobs.put(id, failed);
                        LOG.error("Cannot persist failed state for job {}", id, storageError);
                    }
                }
            }
        } finally {
            synchronized (this) {
                try {
                    Files.deleteIfExists(directory(id).resolve("source"));
                    deleteTree(directory(id).resolve("tmp"));
                    Job current = jobs.get(id);
                    if (current != null && current.status() == Status.CANCELLED) {
                        Files.deleteIfExists(directory(id).resolve("result.json"));
                    }
                } catch (IOException e) {
                    LOG.error("Cannot clean temporary files for job {}", id, e);
                }
                active.remove(id);
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void expire() {
        Instant cutoff = Instant.now().minus(properties.retentionMinutes(), ChronoUnit.MINUTES);
        for (Job job : new ArrayList<>(jobs.values())) {
            if (job.status().terminal() && job.updatedAt().isBefore(cutoff) && !active.contains(job.id())) {
                try {
                    deleteTree(directory(job.id()));
                    jobs.remove(job.id());
                } catch (IOException e) {
                    LOG.error("Cannot expire job {}", job.id(), e);
                }
            }
        }
    }

    private void restore() throws IOException {
        try (var entries = Files.list(storage)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                if (!directory.getFileName().toString().matches("[a-f0-9-]{36}")) continue;
                Path metadata = directory.resolve("job.json");
                if (!Files.isRegularFile(metadata)) {
                    deleteTree(directory);
                    continue;
                }
                Job job = mapper.readValue(metadata.toFile(), Job.class);
                if (!directory.getFileName().toString().equals(job.id())) {
                    throw new IOException("Job directory and stored identifier disagree");
                }
                if (!job.status().terminal()) {
                    job = job.transition(Status.FAILED,
                            ExtractionResult.failure("INTERRUPTED", "The server restarted before extraction completed. Upload again."));
                    writeJson(metadata, job);
                }
                Files.deleteIfExists(directory.resolve("source"));
                deleteTree(directory.resolve("tmp"));
                jobs.put(job.id(), job);
            }
        }
        expire();
    }

    private void save(Job job) throws IOException {
        writeJson(directory(job.id()).resolve("job.json"), job);
        jobs.put(job.id(), job);
    }

    private void writeJson(Path target, Object value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path directory(String id) { return storage.resolve(id); }

    static String safeFilename(String name) {
        if (name == null || name.isBlank()) return "document";
        String cleaned = name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        return cleaned.isEmpty() ? "document" : cleaned.substring(0, Math.min(cleaned.length(), 200));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", e);
        }
    }

    private void deleteTree(Path directory) throws IOException {
        if (!directory.normalize().startsWith(storage) || directory.normalize().equals(storage)) {
            throw new IOException("Refusing deletion outside a job directory");
        }
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        worker.stopAll();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.error("Extraction workers did not stop within the shutdown deadline.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for extraction workers to stop.");
        }
    }
}
