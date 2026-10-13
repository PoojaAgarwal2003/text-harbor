package com.textharbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.textharbor.Models.ExtractionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

@Component
public class ForkedWorker {
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final ExtractionProperties properties;
    private final ObjectMapper mapper;

    public ForkedWorker(ExtractionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public ExtractionResult execute(String id, Path directory, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) {
            return ExtractionResult.failure("CANCELLED", "Extraction was cancelled.");
        }
        List<String> arguments = new ArrayList<>(List.of("-Xmx512m", "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=" + directory.resolve("tmp")));
        Path app = new ApplicationHome(TextHarborApplication.class).getSource().toPath();
        if (Files.isRegularFile(app)) {
            arguments.addAll(List.of("-jar", app.toString()));
        } else {
            arguments.addAll(List.of("-cp", System.getProperty("java.class.path"),
                    TextHarborApplication.class.getName()));
        }
        arguments.addAll(List.of("--extraction-worker", directory.toString()));
        Files.createDirectories(directory.resolve("tmp"));
        Path argumentFile = directory.resolve("worker.args");
        Files.write(argumentFile, arguments.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(executable, "@" + argumentFile);
        builder.environment().put("OMP_THREAD_LIMIT", "1");
        if (properties.tesseractPath() != null && !properties.tesseractPath().isBlank()) {
            String key = builder.environment().keySet().stream()
                    .filter(name -> name.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            builder.environment().put(key, properties.tesseractPath() + java.io.File.pathSeparator
                    + builder.environment().getOrDefault(key, ""));
        }
        Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        running.put(id, process);
        try {
            if (cancelled.getAsBoolean()) stop(process);
            if (!process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS)) {
                stop(process);
                return ExtractionResult.failure("TIMEOUT", "Extraction exceeded the time limit. Try a smaller document.");
            }
            Path result = directory.resolve("result.json");
            if (process.exitValue() != 0 || !Files.isRegularFile(result)) {
                return ExtractionResult.failure("WORKER_FAILED",
                        "The isolated parser stopped unexpectedly or exceeded its memory limit.");
            }
            return mapper.readValue(result.toFile(), ExtractionResult.class);
        } finally {
            running.remove(id);
            if (process.isAlive()) stop(process);
        }
    }

    public void cancel(String id) {
        Process process = running.get(id);
        if (process != null) stop(process);
    }

    public void stopAll() {
        running.values().forEach(ForkedWorker::stop);
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
