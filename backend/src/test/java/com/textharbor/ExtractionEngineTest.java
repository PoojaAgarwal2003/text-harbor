package com.textharbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.textharbor.Models.OcrMode;
import com.textharbor.Models.WorkerRequest;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class ExtractionEngineTest {
    @TempDir Path temporary;

    private WorkerRequest request(String filename) {
        return new WorkerRequest(filename, "eng", OcrMode.AUTO, 100_000, 20, 20, false);
    }

    @Test
    void extractsNativeOfficeDocumentsAndPdf() throws Exception {
        Path word = temporary.resolve("notes.docx");
        try (var document = new XWPFDocument(); var output = Files.newOutputStream(word)) {
            document.createParagraph().createRun().setText("Harbor Word paragraph");
            document.createTable(1, 1).getRow(0).getCell(0).setText("Table content");
            document.write(output);
        }
        assertThat(ExtractionEngine.extract(word, request("notes.docx")).text())
                .contains("Harbor Word paragraph", "Table content");
        Path excel = temporary.resolve("sheet.xlsx");
        try (var workbook = new XSSFWorkbook(); var output = Files.newOutputStream(excel)) {
            workbook.createSheet("Sheet one").createRow(0).createCell(0).setCellValue("Harbor Excel value");
            workbook.write(output);
        }
        assertThat(ExtractionEngine.extract(excel, request("sheet.xlsx")).text()).contains("Harbor Excel value");
        Path slides = temporary.resolve("slides.pptx");
        try (var presentation = new XMLSlideShow(); var output = Files.newOutputStream(slides)) {
            presentation.createSlide().createTextBox().setText("Harbor presentation");
            presentation.write(output);
        }
        assertThat(ExtractionEngine.extract(slides, request("slides.pptx")).text()).contains("Harbor presentation");
        Path pdf = temporary.resolve("native.pdf");
        try (var document = new PDDocument()) {
            addTextPage(document, "Harbor PDF native text");
            document.save(pdf.toFile());
        }
        assertThat(ExtractionEngine.extract(pdf, request("native.pdf")).text()).contains("Harbor PDF native text");
    }

    @Test
    void readsUnicodeAndArchiveEntriesWithoutWritingEmbeddedFiles() throws Exception {
        Path text = temporary.resolve("unicode.txt");
        Files.writeString(text, "Hello नमस्ते café");
        assertThat(ExtractionEngine.extract(text, request("unicode.txt")).text()).contains("Hello नमस्ते café");
        Path zip = temporary.resolve("bundle.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("../../outside.txt"));
            output.write("Safe archive text".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertThat(ExtractionEngine.extract(zip, request("bundle.zip")).text()).contains("Safe archive text");
        assertThat(Files.exists(temporary.getParent().resolve("outside.txt"))).isFalse();
    }

    @Test
    void extractsCommonTextEmailAndRichTextFormats() throws Exception {
        String[][] examples = {
                {"note.rtf", "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Harbor rich text\\par}", "Harbor rich text"},
                {"page.html", "<html><body><h1>Harbor heading</h1><script>doNotExtract()</script></body></html>", "Harbor heading"},
                {"data.json", "{\"title\":\"Harbor JSON\"}", "Harbor JSON"},
                {"data.csv", "title,amount\nHarbor CSV,42", "Harbor CSV"},
                {"note.md", "# Harbor Markdown\n\nText content", "Harbor Markdown"},
                {"mail.eml", "From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: Harbor mail\r\n"
                        + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHarbor email body", "Harbor email body"}
        };
        for (String[] example : examples) {
            Path source = temporary.resolve(example[0]);
            Files.writeString(source, example[1]);
            var result = ExtractionEngine.extract(source, request(example[0]));
            assertThat(result.errorCode()).as(example[0]).isNull();
            assertThat(result.text()).as(example[0]).contains(example[2]).doesNotContain("doNotExtract()");
        }
    }

    @Test
    void reportsTruncationRatherThanClaimingCompleteExtraction() throws Exception {
        Path text = temporary.resolve("long.txt");
        Files.writeString(text, "Harbor text content ".repeat(1000));
        var result = ExtractionEngine.extract(text,
                new WorkerRequest("long.txt", "eng", OcrMode.NEVER, 100, 20, 20, false));
        assertThat(result.errorCode()).isNull();
        assertThat(result.text()).hasSizeLessThanOrEqualTo(100);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("truncated"));
    }

    @Test
    void rejectsUnknownBinaryAndEncryptedPdf() throws Exception {
        Path binary = temporary.resolve("binary.bin");
        Files.write(binary, new byte[]{0, 1, 0, 2, 0, 3, 0, (byte) 255, 0, (byte) 255});
        assertThat(ExtractionEngine.extract(binary, request("binary.bin")).errorCode()).isEqualTo("UNSUPPORTED_FORMAT");
        Path pdf = temporary.resolve("locked.pdf");
        try (var document = new PDDocument()) {
            addTextPage(document, "Private document");
            document.protect(new StandardProtectionPolicy("owner-password", "reader-password", new AccessPermission()));
            document.save(pdf.toFile());
        }
        assertThat(ExtractionEngine.extract(pdf, request("locked.pdf")).errorCode()).isEqualTo("ENCRYPTED_DOCUMENT");
    }

    @Test
    void rejectsPdfPageLimitAndImageWithoutOcr() throws Exception {
        Path pdf = temporary.resolve("pages.pdf");
        try (var document = new PDDocument()) {
            addTextPage(document, "First page");
            addTextPage(document, "Second page");
            document.save(pdf.toFile());
        }
        assertThat(ExtractionEngine.extract(pdf,
                new WorkerRequest("pages.pdf", "eng", OcrMode.AUTO, 1000, 20, 1, false)).errorCode())
                .isEqualTo("PAGE_LIMIT");
        Path image = temporary.resolve("scan.png");
        ImageIO.write(scan(), "png", image.toFile());
        assertThat(ExtractionEngine.extract(image, request("scan.png")).errorCode()).isEqualTo("OCR_UNAVAILABLE");
    }

    @Test
    void disablesExternalXmlEntities() throws Exception {
        Path secret = temporary.resolve("secret.txt");
        Files.writeString(secret, "MUST_NOT_LEAK_8741");
        Path xml = temporary.resolve("document.xml");
        Files.writeString(xml, "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY leak SYSTEM \""
                + secret.toUri() + "\">]><root>Visible &leak;</root>");
        assertThat(ExtractionEngine.extract(xml, request("document.xml")).text()).doesNotContain("MUST_NOT_LEAK_8741");
    }

    @Test
    void rejectsOversizedPdfRenderingDimensions() throws Exception {
        Path pdf = temporary.resolve("huge.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(100_000, 100_000)));
            document.save(pdf.toFile());
        }
        assertThat(ExtractionEngine.extract(pdf, request("huge.pdf")).errorCode()).isEqualTo("IMAGE_LIMIT");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TESSERACT_PATH", matches = ".+")
    void extractsRealImageAndMixedPdfWithLocalOcr() throws Exception {
        Path fixtures = Path.of("target", "smoke-fixtures");
        Files.createDirectories(fixtures);
        BufferedImage scan = scan();
        Path image = fixtures.resolve("scan.png");
        ImageIO.write(scan, "png", image.toFile());
        var imageResult = ExtractionEngine.extract(image,
                new WorkerRequest("scan.png", "eng", OcrMode.AUTO, 100_000, 20, 20, true));
        assertThat(imageResult.errorCode()).isNull();
        assertThat(imageResult.text()).contains("TEXT HARBOR", "LOCAL OCR");
        Path pdf = fixtures.resolve("mixed.pdf");
        Files.deleteIfExists(pdf);
        try (var document = new PDDocument()) {
            addTextPage(document, "Native page from Text Harbor");
            PDPage page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, scan), 20, 500, 570, 160);
            }
            document.save(pdf.toFile());
        }
        var result = ExtractionEngine.extract(pdf,
                new WorkerRequest("mixed.pdf", "eng", OcrMode.AUTO, 100_000, 20, 20, true));
        assertThat(result.errorCode()).isNull();
        assertThat(result.text()).contains("Native page from Text Harbor", "TEXT HARBOR");
    }

    private static void addTextPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (var content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
            content.newLineAtOffset(40, 700);
            content.showText(text);
            content.endText();
        }
    }

    private static BufferedImage scan() {
        BufferedImage image = new BufferedImage(1600, 450, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1600, 450);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 64));
        graphics.drawString("TEXT HARBOR", 80, 160);
        graphics.drawString("LOCAL OCR 2026", 80, 280);
        graphics.dispose();
        return image;
    }
}
