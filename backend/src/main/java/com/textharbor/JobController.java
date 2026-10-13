package com.textharbor;

import com.textharbor.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class JobController {
    private final JobService jobs;
    private final ExtractionProperties properties;
    private final OcrSupport ocr;

    public JobController(JobService jobs, ExtractionProperties properties, OcrSupport ocr) {
        this.jobs = jobs;
        this.properties = properties;
        this.ocr = ocr;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(properties.maxUploadBytes(), properties.maxPages(),
                properties.maxChars(), properties.retentionMinutes(), ocr.available(), ocr.languages(),
                List.of("PDF", "DOC / DOCX", "XLS / XLSX", "PPT / PPTX", "ODF", "RTF",
                        "TXT / CSV / JSON / XML", "HTML / Markdown", "EPUB", "EML / MSG",
                        "ZIP / TAR", "PNG / JPG / TIFF / BMP"));
    }

    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreatedJob create(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "eng") String language,
            @RequestParam(defaultValue = "AUTO") OcrMode ocrMode) throws IOException {
        return jobs.submit(file, language, ocrMode);
    }

    @GetMapping("/jobs/{id}")
    public JobView status(@PathVariable String id,
            @RequestHeader(value = "X-Job-Token", required = false) String token) {
        return jobs.authorized(id, token).view();
    }

    @GetMapping("/jobs/{id}/result")
    public ExtractionResult result(@PathVariable String id,
            @RequestHeader(value = "X-Job-Token", required = false) String token) throws IOException {
        return jobs.result(id, token);
    }

    @GetMapping("/jobs/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id,
            @RequestHeader(value = "X-Job-Token", required = false) String token) throws IOException {
        Job job = jobs.authorized(id, token);
        byte[] text = jobs.result(id, token).text().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(job.filename() + ".txt", StandardCharsets.UTF_8).build().toString())
                .body(text);
    }

    @PostMapping("/jobs/{id}/cancel")
    public JobView cancel(@PathVariable String id,
            @RequestHeader(value = "X-Job-Token", required = false) String token) throws IOException {
        return jobs.cancel(id, token);
    }

    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id,
            @RequestHeader(value = "X-Job-Token", required = false) String token) throws IOException {
        jobs.delete(id, token);
    }
}
