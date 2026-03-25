package com.smartnote.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.smartnote.entity.Note;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class NoteExportService {

    private static final Logger log = LoggerFactory.getLogger(NoteExportService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PDF_FONT_FAMILY = "SmartNotePdfFont";
    private static final List<Path> PDF_FONT_CANDIDATES = List.of(
            Path.of("C:/Windows/Fonts/msyh.ttf"),
            Path.of("C:/Windows/Fonts/simhei.ttf"),
            Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
            Path.of("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"),
            Path.of("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf"),
            Path.of("/usr/local/share/fonts/NotoSansSC-Regular.ttf")
    );

    @Value("${smartnote.export.pdf-font-path:}")
    private String configuredPdfFontPath;

    public byte[] exportMarkdown(Note note) {
        String content = "# " + safeTitle(note) + "\n\n" + defaultString(note.getContent());
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportPdf(Note note) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.toStream(outputStream);
            registerPdfFont(builder);
            builder.withHtmlContent(buildPdfHtml(note), null);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to export note as PDF", exception);
        }
    }

    public byte[] exportWord(Note note) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writeWordDocument(document, note);
            document.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new RuntimeException("Failed to export note as Word", exception);
        }
    }

    private void writeWordDocument(XWPFDocument document, Note note) {
        XWPFParagraph titleParagraph = document.createParagraph();
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titleParagraph.createRun();
        titleRun.setText(safeTitle(note));
        titleRun.setBold(true);
        titleRun.setFontFamily("Microsoft YaHei");
        titleRun.setFontSize(18);

        XWPFParagraph metaParagraph = document.createParagraph();
        metaParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun metaRun = metaParagraph.createRun();
        metaRun.setFontFamily("Microsoft YaHei");
        metaRun.setFontSize(10);
        metaRun.setColor("6B7280");
        metaRun.setText("Exported at: " + DATE_TIME_FORMATTER.format(LocalDateTime.now()));
        if (note.getUpdatedAt() != null) {
            metaRun.addBreak();
            metaRun.setText("Last updated: " + DATE_TIME_FORMATTER.format(note.getUpdatedAt()));
        }

        document.createParagraph().createRun().addBreak();

        Document htmlDocument = Jsoup.parseBodyFragment(resolveContentHtml(note));
        for (Node node : htmlDocument.body().childNodes()) {
            appendBlock(document, node);
        }
    }

    private void appendBlock(XWPFDocument document, Node node) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text().trim();
            if (!text.isEmpty()) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setSpacingAfter(120);
                appendStyledText(paragraph, text, TextStyle.normal());
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tagName = element.tagName().toLowerCase();
        switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> appendHeading(document, element, Integer.parseInt(tagName.substring(1)));
            case "p" -> appendParagraph(document, element);
            case "blockquote" -> appendQuote(document, element);
            case "pre" -> appendCodeBlock(document, element);
            case "ul" -> appendList(document, element, false);
            case "ol" -> appendList(document, element, true);
            case "table" -> appendTableFallback(document, element);
            case "hr" -> {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setBorderBottom(Borders.SINGLE);
                paragraph.createRun().addBreak();
            }
            default -> {
                if (!element.text().isBlank() && element.children().isEmpty()) {
                    XWPFParagraph paragraph = document.createParagraph();
                    paragraph.setSpacingAfter(120);
                    appendStyledText(paragraph, element.text(), TextStyle.normal());
                } else {
                    for (Node child : element.childNodes()) {
                        appendBlock(document, child);
                    }
                }
            }
        }
    }

    private void appendHeading(XWPFDocument document, Element element, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(160);
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(switch (level) {
            case 1 -> 18;
            case 2 -> 16;
            case 3 -> 14;
            default -> 12;
        });
        run.setText(element.text());
    }

    private void appendParagraph(XWPFDocument document, Element element) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        appendStyledNodes(paragraph, element.childNodes(), TextStyle.normal());
    }

    private void appendQuote(XWPFDocument document, Element element) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(420);
        paragraph.setBorderLeft(Borders.SINGLE);
        paragraph.setSpacingAfter(120);
        appendStyledNodes(paragraph, element.childNodes(), TextStyle.normal().withItalic());
    }

    private void appendCodeBlock(XWPFDocument document, Element element) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        paragraph.setIndentationLeft(280);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Consolas");
        run.setFontSize(10);
        run.setColor("334155");
        run.setText(element.text());
    }

    private void appendList(XWPFDocument document, Element listElement, boolean ordered) {
        int index = 1;
        for (Element item : listElement.children()) {
            if (!"li".equalsIgnoreCase(item.tagName())) {
                continue;
            }

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setIndentationLeft(360);
            paragraph.setSpacingAfter(80);
            appendStyledText(paragraph, ordered ? index + ". " : "* ", TextStyle.normal());
            appendStyledNodes(paragraph, item.childNodes(), TextStyle.normal());
            index += 1;
        }
    }

    private void appendTableFallback(XWPFDocument document, Element table) {
        for (Element row : table.select("tr")) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setSpacingAfter(80);
            String rowText = row.select("th,td").eachText().stream()
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
            appendStyledText(paragraph, rowText, TextStyle.normal().withBold());
        }
    }

    private void appendStyledNodes(XWPFParagraph paragraph, List<Node> nodes, TextStyle style) {
        for (Node child : nodes) {
            appendStyledNode(paragraph, child, style);
        }
    }

    private void appendStyledNode(XWPFParagraph paragraph, Node node, TextStyle style) {
        if (node instanceof TextNode textNode) {
            if (!textNode.text().isEmpty()) {
                appendStyledText(paragraph, textNode.text(), style);
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        TextStyle nextStyle = style;
        switch (element.tagName().toLowerCase()) {
            case "strong", "b" -> nextStyle = nextStyle.withBold();
            case "em", "i" -> nextStyle = nextStyle.withItalic();
            case "u" -> nextStyle = nextStyle.withUnderline();
            case "code" -> nextStyle = nextStyle.withCode();
            case "br" -> {
                XWPFRun run = paragraph.createRun();
                run.addBreak(BreakType.TEXT_WRAPPING);
                return;
            }
            default -> {
                // Keep current style.
            }
        }

        if ("a".equalsIgnoreCase(element.tagName())) {
            appendStyledNodes(paragraph, element.childNodes(), nextStyle.withUnderline());
            String href = element.attr("href");
            if (href != null && !href.isBlank()) {
                appendStyledText(paragraph, " (" + href + ")", TextStyle.normal().withItalic());
            }
            return;
        }

        appendStyledNodes(paragraph, element.childNodes(), nextStyle);
    }

    private void appendStyledText(XWPFParagraph paragraph, String text, TextStyle style) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(style.code ? "Consolas" : "Microsoft YaHei");
        run.setFontSize(style.code ? 10 : 11);
        run.setBold(style.bold);
        run.setItalic(style.italic);
        run.setUnderline(style.underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
        run.setColor(style.code ? "334155" : "111827");
        run.setText(text);
    }

    private void registerPdfFont(PdfRendererBuilder builder) {
        for (Path candidate : resolvePdfFontCandidates()) {
            if (!isSupportedPdfFont(candidate)) {
                continue;
            }

            try {
                builder.useFont(candidate.toFile(), PDF_FONT_FAMILY);
                log.info("Registered PDF font: {}", candidate);
                return;
            } catch (Exception exception) {
                log.warn("Skipping PDF font {} because registration failed: {}", candidate, exception.getMessage());
            }
        }

        log.info("No custom PDF font registered. PDF export will use renderer fallback fonts.");
    }

    private List<Path> resolvePdfFontCandidates() {
        List<Path> candidates = new ArrayList<>();
        if (configuredPdfFontPath != null && !configuredPdfFontPath.isBlank()) {
            try {
                candidates.add(Path.of(configuredPdfFontPath.trim()));
            } catch (Exception exception) {
                log.warn("Ignoring invalid configured PDF font path '{}': {}", configuredPdfFontPath, exception.getMessage());
            }
        }
        candidates.addAll(PDF_FONT_CANDIDATES);
        return candidates;
    }

    private boolean isSupportedPdfFont(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return false;
        }

        String fileName = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".ttf")) {
            log.warn("Skipping unsupported PDF font file (expected .ttf): {}", candidate);
            return false;
        }

        return true;
    }

    private String buildPdfHtml(Note note) {
        String template = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8" />
                    <style>
                        @page { size: A4; margin: 24mm 18mm 20mm 18mm; }
                        body { font-family: '%s', 'Microsoft YaHei', 'SimHei', 'Noto Sans SC', 'PingFang SC', sans-serif; color: #111827; font-size: 12px; line-height: 1.75; }
                        h1, h2, h3, h4, h5, h6 { color: #0f172a; margin: 18px 0 10px; }
                        p, li, blockquote, pre { margin: 0 0 10px; }
                        blockquote { border-left: 4px solid #cbd5e1; padding: 8px 0 8px 12px; color: #475569; background: #f8fafc; }
                        pre { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 10px 12px; white-space: pre-wrap; }
                        code { font-family: 'Consolas', 'Courier New', monospace; }
                        img { max-width: 100%; }
                        hr { border: none; border-top: 1px solid #e5e7eb; margin: 18px 0; }
                        .doc-title { text-align: center; font-size: 26px; font-weight: 700; margin-bottom: 8px; }
                        .doc-meta { text-align: center; color: #64748b; font-size: 11px; margin-bottom: 22px; }
                    </style>
                </head>
                <body>
                    <div class="doc-title">%s</div>
                    <div class="doc-meta">Last updated: %s</div>
                    %s
                </body>
                </html>
                """;
        String updatedAt = note.getUpdatedAt() == null ? "" : DATE_TIME_FORMATTER.format(note.getUpdatedAt());
        return template.formatted(PDF_FONT_FAMILY, escapeHtml(safeTitle(note)), escapeHtml(updatedAt), resolveContentHtml(note));
    }

    private String resolveContentHtml(Note note) {
        String html = note.getContentHtml();
        if (html == null || html.isBlank()) {
            html = "<p>" + escapeHtml(defaultString(note.getContent())).replace("\n", "<br/>") + "</p>";
        }

        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        return document.body().html();
    }

    private String safeTitle(Note note) {
        String title = note.getTitle();
        return title == null || title.isBlank() ? "Untitled Note" : title.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record TextStyle(boolean bold, boolean italic, boolean underline, boolean code) {
        private static TextStyle normal() {
            return new TextStyle(false, false, false, false);
        }

        private TextStyle withBold() {
            return new TextStyle(true, italic, underline, code);
        }

        private TextStyle withItalic() {
            return new TextStyle(bold, true, underline, code);
        }

        private TextStyle withUnderline() {
            return new TextStyle(bold, italic, true, code);
        }

        private TextStyle withCode() {
            return new TextStyle(bold, italic, underline, true);
        }
    }
}
