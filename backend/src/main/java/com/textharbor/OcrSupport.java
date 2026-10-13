package com.textharbor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OcrSupport {
    private static final Logger LOG = LoggerFactory.getLogger(OcrSupport.class);
    private final List<String> languages;

    public OcrSupport(ExtractionProperties properties) {
        languages = discover(properties.tesseractPath());
        if (languages.isEmpty()) {
            LOG.warn("Tesseract or language data unavailable. Native text extraction remains enabled.");
        }
    }

    public List<String> languages() { return languages; }
    public boolean available() { return !languages.isEmpty(); }

    private List<String> discover(String directory) {
        String executable = directory == null || directory.isBlank()
                ? "tesseract" : Path.of(directory, "tesseract").toString();
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "--list-langs")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("Tesseract language discovery timed out.");
                return List.of();
            }
            if (process.exitValue() != 0) {
                LOG.warn("Tesseract language discovery failed with exit code {}.", process.exitValue());
                return List.of();
            }
            return new String(process.getInputStream().readNBytes(65536), StandardCharsets.UTF_8)
                    .lines().map(String::trim)
                    .filter(line -> line.matches("[a-zA-Z][a-zA-Z0-9_/-]{1,60}"))
                    .filter(line -> !line.equals("osd")).distinct().sorted().toList();
        } catch (IOException e) {
            LOG.warn("Cannot start local Tesseract: {}", e.getClass().getSimpleName());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return List.of();
        }
    }
}
