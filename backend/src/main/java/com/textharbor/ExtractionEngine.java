package com.textharbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.textharbor.Models.ExtractionResult;
import com.textharbor.Models.OcrMode;
import com.textharbor.Models.WorkerRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public final class ExtractionEngine {
    private ExtractionEngine() {}

    public static void runWorker(Path directory) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        WorkerRequest request = mapper.readValue(directory.resolve("request.json").toFile(), WorkerRequest.class);
        ExtractionResult result = extract(directory.resolve("source"), request);
        mapper.writeValue(directory.resolve("result.json").toFile(), result);
    }

    public static ExtractionResult extract(Path source, WorkerRequest request) {
        BodyContentHandler handler = new BodyContentHandler(request.maxChars());
        List<String> warnings = new ArrayList<>();
        String mediaType = "application/octet-stream";
        try {
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, request.filename());
            try (TikaInputStream input = TikaInputStream.get(source)) {
                mediaType = parser.getDetector().detect(input, metadata).toString();
            }
            if (mediaType.startsWith("audio/") || mediaType.startsWith("video/")
                    || mediaType.equals("application/octet-stream")
                    || mediaType.contains("executable") || mediaType.contains("x-dosexec")) {
                return ExtractionResult.failure("UNSUPPORTED_FORMAT",
                        "This file has no supported document text. Audio/video transcription and executables are not supported.");
            }
            if (mediaType.equals("application/pdf")) {
                try (var pdf = Loader.loadPDF(source.toFile())) {
                    if (!pdf.getCurrentAccessPermission().canExtractContent()) {
                        return ExtractionResult.failure("ENCRYPTED_DOCUMENT", "This PDF does not permit text extraction.");
                    }
                    if (pdf.getNumberOfPages() > request.maxPages()) {
                        return ExtractionResult.failure("PAGE_LIMIT", "PDF exceeds the configured page limit.");
                    }
                }
            }
            boolean useOcr = request.ocrAvailable() && request.ocrMode() != OcrMode.NEVER;
            if (mediaType.startsWith("image/") && !mediaType.equals("image/svg+xml") && !useOcr) {
                return ExtractionResult.failure("OCR_UNAVAILABLE",
                        "Image text requires OCR. Install Tesseract language data and enable OCR.");
            }
            if (mediaType.equals("application/pdf") && !useOcr) {
                warnings.add("OCR was disabled or unavailable. Scanned pages and image text may be missing.");
            }
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);
            TesseractOCRConfig ocr = new TesseractOCRConfig();
            ocr.setSkipOcr(!useOcr);
            ocr.setLanguage(request.language());
            ocr.setTimeoutSeconds(45);
            context.set(TesseractOCRConfig.class, ocr);
            PDFParserConfig pdf = new PDFParserConfig();
            pdf.setSortByPosition(true);
            pdf.setOcrDPI(300);
            pdf.setExtractInlineImages(false);
            pdf.setOcrStrategy(!useOcr ? PDFParserConfig.OCR_STRATEGY.NO_OCR
                    : request.ocrMode() == OcrMode.ALWAYS ? PDFParserConfig.OCR_STRATEGY.OCR_ONLY
                    : PDFParserConfig.OCR_STRATEGY.AUTO);
            context.set(PDFParserConfig.class, pdf);
            AtomicInteger embeddedCount = new AtomicInteger();
            ParsingEmbeddedDocumentExtractor delegate = new ParsingEmbeddedDocumentExtractor(context);
            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata embedded) {
                    if (embeddedCount.incrementAndGet() > request.maxEmbedded()) {
                        addWarning(warnings, "Embedded-file limit reached. Some attachments were not extracted.");
                        return false;
                    }
                    return delegate.shouldParseEmbedded(embedded);
                }

                @Override
                public void parseEmbedded(InputStream stream, ContentHandler output,
                        Metadata embedded, boolean outputHtml) throws SAXException, IOException {
                    if (!useOcr) {
                        addWarning(warnings, "Embedded files were found with OCR disabled; embedded image text may be missing.");
                    }
                    delegate.parseEmbedded(stream, output, embedded, outputHtml);
                }
            });
            if (!parser.getSupportedTypes(context).contains(MediaType.parse(mediaType))) {
                return ExtractionResult.failure("UNSUPPORTED_FORMAT",
                        "The file was detected, but no local text parser supports this format.");
            }
            try (TikaInputStream input = TikaInputStream.get(source)) {
                parser.parse(input, handler, metadata, context);
            }
            for (String name : metadata.names()) {
                if (name.startsWith("X-TIKA:EXCEPTION")) {
                    addWarning(warnings, "A parser reported incomplete content. Review the source document.");
                }
            }
        } catch (Exception e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                addWarning(warnings, "Text limit reached. Output is truncated.");
            } else if (hasCause(e, EncryptedDocumentException.class) || hasCause(e, InvalidPasswordException.class)) {
                return ExtractionResult.failure("ENCRYPTED_DOCUMENT",
                        "Password-protected documents are not supported. Upload an unlocked copy you are authorized to read.");
            } else if (handler.toString().isBlank()) {
                return ExtractionResult.failure("EXTRACTION_FAILED",
                        "The document could not be read. It may be damaged, unsupported, or exceed parser safety limits.");
            } else {
                addWarning(warnings, "Extraction stopped before completion (" + e.getClass().getSimpleName() + ").");
            }
        }
        String text = handler.toString().strip();
        if (text.isBlank()) {
            return ExtractionResult.failure("NO_TEXT_FOUND",
                    "No readable text was found. For scanned PDFs, enable OCR or try whole-page OCR.");
        }
        return new ExtractionResult(text, mediaType, List.copyOf(warnings), null, null);
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) warnings.add(warning);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }
}
