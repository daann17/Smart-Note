package com.smartnote.service;

import com.smartnote.dto.NoteRequest;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NoteImportService {

    private static final Pattern MARKDOWN_TITLE_PATTERN = Pattern.compile("^#\\s+(.+?)\\s*$");
    private static final Pattern WORD_HEADING_STYLE_PATTERN = Pattern.compile("(heading|标题)\\s*([1-6])", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_EXPORT_META_PATTERN = Pattern.compile("(?i)^(exported at:|last updated:).*$");

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .escapeHtml(false)
            .build();

    public NoteRequest buildImportRequest(Long notebookId, MultipartFile file, String formatHint) {
        if (notebookId == null) {
            throw new IllegalArgumentException("Notebook id is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }

        String fallbackTitle = resolveFileBaseName(file.getOriginalFilename());
        String extension = extensionOf(file.getOriginalFilename());
        ImportFormat format = resolveImportFormat(file, formatHint);
        ImportedNote importedNote;

        try {
            byte[] bytes = file.getBytes();
            importedNote = switch (format) {
                case TEXT -> importText(bytes, fallbackTitle);
                case MARKDOWN -> importMarkdown(bytes, fallbackTitle);
                case HTML -> importHtml(bytes, fallbackTitle);
                case WORD -> isLegacyWordFile(file, extension)
                        ? importLegacyWord(bytes, fallbackTitle)
                        : importWord(bytes, fallbackTitle);
                case EXCEL -> importSpreadsheet(bytes, fallbackTitle);
            };
        } catch (IOException exception) {
            throw new RuntimeException("读取导入文件失败，请重试", exception);
        }

        NoteRequest request = new NoteRequest();
        request.setNotebookId(notebookId);
        request.setTitle(importedNote.title());
        request.setContent(importedNote.markdown());
        request.setContentHtml(importedNote.html());
        request.setStatus("DRAFT");
        return request;
    }

    private ImportedNote importText(byte[] bytes, String fallbackTitle) {
        String markdown = normalizeImportedMarkdown(new String(bytes, StandardCharsets.UTF_8));
        return new ImportedNote(fallbackTitle, markdown, renderMarkdown(markdown));
    }

    private ImportedNote importMarkdown(byte[] bytes, String fallbackTitle) {
        String rawMarkdown = normalizeLineEndings(new String(bytes, StandardCharsets.UTF_8));
        List<String> lines = rawMarkdown.lines().toList();
        int firstContentLine = 0;
        while (firstContentLine < lines.size() && lines.get(firstContentLine).isBlank()) {
            firstContentLine += 1;
        }

        String title = fallbackTitle;
        if (firstContentLine < lines.size()) {
            Matcher matcher = MARKDOWN_TITLE_PATTERN.matcher(lines.get(firstContentLine));
            if (matcher.matches()) {
                title = cleanTitle(matcher.group(1), fallbackTitle);
                firstContentLine += 1;
                while (firstContentLine < lines.size() && lines.get(firstContentLine).isBlank()) {
                    firstContentLine += 1;
                }
            }
        }

        String markdown = normalizeImportedMarkdown(String.join("\n", lines.subList(Math.min(firstContentLine, lines.size()), lines.size())));
        return new ImportedNote(title, markdown, renderMarkdown(markdown));
    }

    private ImportedNote importHtml(byte[] bytes, String fallbackTitle) {
        String html = new String(bytes, StandardCharsets.UTF_8);
        Document document = Jsoup.parse(html);
        document.select("script, style, meta, link").remove();

        Element contentRoot = document.body();
        Element exportedShell = document.selectFirst(".doc-shell");
        if (exportedShell != null) {
            contentRoot = exportedShell;
        }

        String title = fallbackTitle;
        Element exportedTitle = contentRoot.selectFirst(".doc-title");
        if (exportedTitle != null && StringUtils.hasText(exportedTitle.text())) {
            title = cleanTitle(exportedTitle.text(), fallbackTitle);
        } else if (StringUtils.hasText(document.title())) {
            title = cleanTitle(document.title(), fallbackTitle);
        }

        removeExportScaffold(contentRoot);
        String markdown = normalizeImportedMarkdown(convertHtmlChildrenToMarkdown(contentRoot.childNodes()));
        return new ImportedNote(title, markdown, renderMarkdown(markdown));
    }

    private ImportedNote importWord(byte[] bytes, String fallbackTitle) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<IBodyElement> bodyElements = document.getBodyElements();
            int contentStartIndex = 0;
            String title = fallbackTitle;

            int firstParagraphIndex = findFirstNonBlankParagraphIndex(bodyElements);
            if (firstParagraphIndex >= 0) {
                XWPFParagraph firstParagraph = (XWPFParagraph) bodyElements.get(firstParagraphIndex);
                int secondParagraphIndex = findNextNonBlankParagraphIndex(bodyElements, firstParagraphIndex + 1);
                if (secondParagraphIndex >= 0) {
                    XWPFParagraph secondParagraph = (XWPFParagraph) bodyElements.get(secondParagraphIndex);
                    if (looksLikeExportMetadata(secondParagraph) && firstParagraph.getAlignment() == ParagraphAlignment.CENTER) {
                        title = cleanTitle(firstParagraph.getText(), fallbackTitle);
                        contentStartIndex = secondParagraphIndex + 1;
                    }
                }
            }

            StringBuilder markdown = new StringBuilder();
            for (int index = contentStartIndex; index < bodyElements.size(); index += 1) {
                appendWordBodyElement(markdown, bodyElements.get(index), document);
            }

            String normalizedMarkdown = normalizeImportedMarkdown(markdown.toString());
            return new ImportedNote(title, normalizedMarkdown, renderMarkdown(normalizedMarkdown));
        } catch (IOException exception) {
            throw new RuntimeException("Word file parsing failed. Please confirm the file is .docx", exception);
        }
    }

    private ImportedNote importLegacyWord(byte[] bytes, String fallbackTitle) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            String markdown = normalizeImportedMarkdown(normalizeLineEndings(extractor.getText()));
            return new ImportedNote(fallbackTitle, markdown, renderMarkdown(markdown));
        } catch (IOException exception) {
            throw new RuntimeException("Word file parsing failed. Please confirm the file is .doc", exception);
        }
    }

    private ImportedNote importSpreadsheet(byte[] bytes, String fallbackTitle) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            StringBuilder markdown = new StringBuilder();
            int sheetCount = workbook.getNumberOfSheets();

            for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex += 1) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<List<String>> rows = extractSpreadsheetRows(sheet, formatter, evaluator);

                if (sheetCount > 1) {
                    markdown.append("## ")
                            .append(escapeMarkdownText(sheet.getSheetName()))
                            .append("\n\n");
                }

                if (rows.isEmpty()) {
                    markdown.append("_Empty sheet_\n\n");
                    continue;
                }

                appendSpreadsheetTable(markdown, rows);
            }

            String normalizedMarkdown = normalizeImportedMarkdown(markdown.toString());
            return new ImportedNote(fallbackTitle, normalizedMarkdown, renderMarkdown(normalizedMarkdown));
        } catch (IOException exception) {
            throw new RuntimeException("Excel file parsing failed. Please confirm the file is .xls or .xlsx", exception);
        }
    }

    private List<List<String>> extractSpreadsheetRows(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<List<String>> rows = new ArrayList<>();
        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();

        for (int rowIndex = firstRowNum; rowIndex <= lastRowNum; rowIndex += 1) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            int lastCell = row.getLastCellNum();
            if (lastCell <= 0) {
                continue;
            }

            List<String> cells = new ArrayList<>();
            boolean hasValue = false;
            for (int cellIndex = 0; cellIndex < lastCell; cellIndex += 1) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : normalizeInlineSpacing(formatter.formatCellValue(cell, evaluator));
                if (StringUtils.hasText(value)) {
                    hasValue = true;
                }
                cells.add(escapeMarkdownTableCell(value));
            }

            if (!hasValue) {
                continue;
            }

            trimTrailingEmptyCells(cells);
            rows.add(cells);
        }

        return rows;
    }

    private void trimTrailingEmptyCells(List<String> cells) {
        while (!cells.isEmpty() && !StringUtils.hasText(cells.get(cells.size() - 1))) {
            cells.remove(cells.size() - 1);
        }
    }

    private void appendSpreadsheetTable(StringBuilder markdown, List<List<String>> rows) {
        int maxColumns = rows.stream()
                .mapToInt(List::size)
                .max()
                .orElse(0);
        if (maxColumns == 0) {
            markdown.append("_Empty sheet_\n\n");
            return;
        }

        List<String> header = rows.get(0);
        markdown.append("| ");
        for (int column = 0; column < maxColumns; column += 1) {
            markdown.append(column < header.size() ? header.get(column) : "");
            markdown.append(" | ");
        }
        markdown.append('\n');

        markdown.append("| ");
        for (int column = 0; column < maxColumns; column += 1) {
            markdown.append("--- | ");
        }
        markdown.append('\n');

        if (rows.size() == 1) {
            markdown.append('\n');
            return;
        }

        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex += 1) {
            List<String> row = rows.get(rowIndex);
            markdown.append("| ");
            for (int column = 0; column < maxColumns; column += 1) {
                markdown.append(column < row.size() ? row.get(column) : "");
                markdown.append(" | ");
            }
            markdown.append('\n');
        }

        markdown.append('\n');
    }

    private boolean isLegacyWordFile(MultipartFile file, String extension) {
        if (".doc".equalsIgnoreCase(extension)) {
            return true;
        }

        String contentType = file.getContentType();
        return contentType != null && "application/msword".equalsIgnoreCase(contentType.trim());
    }

    private ImportFormat resolveImportFormat(MultipartFile file, String formatHint) {
        if (StringUtils.hasText(formatHint)) {
            return ImportFormat.fromHint(formatHint);
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!extension.isBlank()) {
            return ImportFormat.fromExtension(extension);
        }

        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)) {
            return ImportFormat.fromContentType(contentType);
        }

        throw new IllegalArgumentException("无法识别导入文件格式");
    }

    private String resolveFileBaseName(String originalFileName) {
        if (!StringUtils.hasText(originalFileName)) {
            return "导入笔记";
        }

        String normalized = originalFileName.replace('\\', '/');
        int lastSlashIndex = normalized.lastIndexOf('/');
        String fileName = lastSlashIndex >= 0 ? normalized.substring(lastSlashIndex + 1) : normalized;
        int lastDotIndex = fileName.lastIndexOf('.');
        String baseName = lastDotIndex > 0 ? fileName.substring(0, lastDotIndex) : fileName;
        return cleanTitle(baseName, "导入笔记");
    }

    private void removeExportScaffold(Element contentRoot) {
        List<Element> removableChildren = new ArrayList<>();
        for (Element child : contentRoot.children()) {
            if (child.hasClass("doc-title") || child.hasClass("doc-meta")) {
                removableChildren.add(child);
            }
        }

        for (Element child : removableChildren) {
            child.remove();
        }
    }

    private int findFirstNonBlankParagraphIndex(List<IBodyElement> bodyElements) {
        for (int index = 0; index < bodyElements.size(); index += 1) {
            if (bodyElements.get(index) instanceof XWPFParagraph paragraph && StringUtils.hasText(paragraph.getText())) {
                return index;
            }
        }
        return -1;
    }

    private int findNextNonBlankParagraphIndex(List<IBodyElement> bodyElements, int startIndex) {
        for (int index = startIndex; index < bodyElements.size(); index += 1) {
            if (bodyElements.get(index) instanceof XWPFParagraph paragraph && StringUtils.hasText(paragraph.getText())) {
                return index;
            }
        }
        return -1;
    }

    private boolean looksLikeExportMetadata(XWPFParagraph paragraph) {
        String text = paragraph.getText();
        return StringUtils.hasText(text) && WORD_EXPORT_META_PATTERN.matcher(text.trim()).matches();
    }

    private void appendWordBodyElement(StringBuilder markdown, IBodyElement bodyElement, XWPFDocument document) {
        if (bodyElement instanceof XWPFParagraph paragraph) {
            appendWordParagraph(markdown, paragraph, document);
            return;
        }

        if (bodyElement instanceof XWPFTable table) {
            appendWordTable(markdown, table);
        }
    }

    private void appendWordParagraph(StringBuilder markdown, XWPFParagraph paragraph, XWPFDocument document) {
        String inlineMarkdown = renderWordParagraphInline(paragraph, document).trim();
        if (!StringUtils.hasText(inlineMarkdown)) {
            return;
        }

        Integer headingLevel = resolveWordHeadingLevel(paragraph);
        if (headingLevel != null) {
            markdown.append("#".repeat(headingLevel))
                    .append(' ')
                    .append(inlineMarkdown)
                    .append("\n\n");
            return;
        }

        if (paragraph.getNumID() != null) {
            int indentLevel = paragraph.getNumIlvl() == null ? 0 : paragraph.getNumIlvl().intValue();
            boolean ordered = isOrderedWordList(paragraph);
            markdown.append("  ".repeat(Math.max(indentLevel, 0)))
                    .append(ordered ? "1. " : "- ")
                    .append(inlineMarkdown)
                    .append('\n');
            return;
        }

        markdown.append(inlineMarkdown).append("\n\n");
    }

    private Integer resolveWordHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (!StringUtils.hasText(style)) {
            return null;
        }

        Matcher matcher = WORD_HEADING_STYLE_PATTERN.matcher(style);
        if (!matcher.find()) {
            return null;
        }

        try {
            int level = Integer.parseInt(matcher.group(2));
            return Math.min(Math.max(level, 1), 6);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isOrderedWordList(XWPFParagraph paragraph) {
        String format = paragraph.getNumFmt();
        if (!StringUtils.hasText(format)) {
            return false;
        }

        return !format.toLowerCase(Locale.ROOT).contains("bullet");
    }

    private String renderWordParagraphInline(XWPFParagraph paragraph, XWPFDocument document) {
        StringBuilder inlineMarkdown = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            if (run instanceof XWPFHyperlinkRun hyperlinkRun) {
                appendWordHyperlink(inlineMarkdown, hyperlinkRun, document);
            } else {
                appendWordRun(inlineMarkdown, run);
            }
        }

        return normalizeInlineSpacing(inlineMarkdown.toString());
    }

    private void appendWordHyperlink(StringBuilder inlineMarkdown, XWPFHyperlinkRun hyperlinkRun, XWPFDocument document) {
        String text = extractRunText(hyperlinkRun);
        XWPFHyperlink hyperlink = hyperlinkRun.getHyperlink(document);
        String url = hyperlink == null ? null : hyperlink.getURL();

        if (StringUtils.hasText(url)) {
            String label = StringUtils.hasText(text) ? text : url;
            inlineMarkdown.append('[')
                    .append(label)
                    .append("](")
                    .append(url.trim())
                    .append(')');
        } else if (StringUtils.hasText(text)) {
            inlineMarkdown.append(applyRunStyles(text, hyperlinkRun));
        }

        appendRunPictures(inlineMarkdown, hyperlinkRun);
    }

    private void appendWordRun(StringBuilder inlineMarkdown, XWPFRun run) {
        String text = extractRunText(run);
        if (StringUtils.hasText(text)) {
            inlineMarkdown.append(applyRunStyles(text, run));
        }

        appendRunPictures(inlineMarkdown, run);
    }

    private void appendRunPictures(StringBuilder inlineMarkdown, XWPFRun run) {
        for (XWPFPicture picture : run.getEmbeddedPictures()) {
            XWPFPictureData pictureData = picture.getPictureData();
            if (pictureData == null || pictureData.getData() == null || pictureData.getData().length == 0) {
                continue;
            }

            String altText = StringUtils.hasText(pictureData.getFileName()) ? pictureData.getFileName() : "image";
            inlineMarkdown.append(" ![")
                    .append(escapeMarkdownText(altText))
                    .append("](")
                    .append(buildPictureDataUri(pictureData))
                    .append(')');
        }
    }

    private String extractRunText(XWPFRun run) {
        String text = run.text();
        if (text == null) {
            text = "";
        }

        if (text.isEmpty()) {
            String indexedText = run.getText(0);
            if (indexedText != null) {
                text = indexedText;
            }
        }

        if (text.isEmpty()) {
            String pictureText = run.getPictureText();
            if (pictureText != null) {
                text = pictureText;
            }
        }

        return text.replace('\u00A0', ' ').replace("\r", "");
    }

    private String applyRunStyles(String text, XWPFRun run) {
        if (isCodeLikeRun(run)) {
            return '`' + text.replace("`", "\\`") + '`';
        }

        String normalizedText = escapeMarkdownText(text);
        if (run.getUnderline() != null && run.getUnderline() != UnderlinePatterns.NONE) {
            normalizedText = "<u>" + normalizedText + "</u>";
        }

        if (run.isBold() && run.isItalic()) {
            return "***" + normalizedText + "***";
        }
        if (run.isBold()) {
            return "**" + normalizedText + "**";
        }
        if (run.isItalic()) {
            return "*" + normalizedText + "*";
        }

        return normalizedText;
    }

    private boolean isCodeLikeRun(XWPFRun run) {
        String fontFamily = run.getFontFamily();
        if (!StringUtils.hasText(fontFamily)) {
            return false;
        }

        String normalized = fontFamily.toLowerCase(Locale.ROOT);
        return normalized.contains("consolas") || normalized.contains("courier");
    }

    private String buildPictureDataUri(XWPFPictureData pictureData) {
        String extension = pictureData.suggestFileExtension();
        String mimeType = mimeTypeForExtension(extension);
        String base64 = Base64.getEncoder().encodeToString(pictureData.getData());
        return "data:" + mimeType + ";base64," + base64;
    }

    private String mimeTypeForExtension(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }

        return switch (normalized) {
            case "png" -> "image/png";
            case "jpg", "jpeg", "jfif" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    private void appendWordTable(StringBuilder markdown, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<List<String>> renderedRows = new ArrayList<>();
        int maxColumns = 0;
        for (XWPFTableRow row : rows) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(escapeMarkdownTableCell(cell.getText()));
            }
            maxColumns = Math.max(maxColumns, cells.size());
            renderedRows.add(cells);
        }

        if (maxColumns == 0) {
            return;
        }

        markdown.append("| ");
        for (int column = 0; column < maxColumns; column += 1) {
            markdown.append(column < renderedRows.get(0).size() ? renderedRows.get(0).get(column) : "");
            markdown.append(" | ");
        }
        markdown.append('\n');

        markdown.append("| ");
        for (int column = 0; column < maxColumns; column += 1) {
            markdown.append("--- | ");
        }
        markdown.append('\n');

        for (int rowIndex = 1; rowIndex < renderedRows.size(); rowIndex += 1) {
            markdown.append("| ");
            List<String> row = renderedRows.get(rowIndex);
            for (int column = 0; column < maxColumns; column += 1) {
                markdown.append(column < row.size() ? row.get(column) : "");
                markdown.append(" | ");
            }
            markdown.append('\n');
        }

        markdown.append('\n');
    }

    private String convertHtmlChildrenToMarkdown(List<Node> nodes) {
        StringBuilder markdown = new StringBuilder();
        for (Node node : nodes) {
            appendHtmlBlock(markdown, node, 0);
        }
        return markdown.toString();
    }

    private void appendHtmlBlock(StringBuilder markdown, Node node, int listDepth) {
        if (node instanceof TextNode textNode) {
            String text = normalizeInlineSpacing(textNode.getWholeText());
            if (StringUtils.hasText(text)) {
                markdown.append(escapeMarkdownText(text)).append("\n\n");
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tagName = element.tagName().toLowerCase(Locale.ROOT);
        switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String heading = normalizeInlineSpacing(renderInlineHtml(element.childNodes()));
                if (StringUtils.hasText(heading)) {
                    markdown.append("#".repeat(Integer.parseInt(tagName.substring(1))))
                            .append(' ')
                            .append(heading)
                            .append("\n\n");
                }
            }
            case "p" -> appendHtmlParagraph(markdown, element);
            case "blockquote" -> appendHtmlBlockquote(markdown, element);
            case "pre" -> appendHtmlCodeBlock(markdown, element);
            case "ul" -> appendHtmlList(markdown, element, listDepth, false);
            case "ol" -> appendHtmlList(markdown, element, listDepth, true);
            case "table" -> appendHtmlTable(markdown, element);
            case "img" -> appendHtmlImage(markdown, element, listDepth);
            case "hr" -> markdown.append("---\n\n");
            case "br" -> markdown.append("  \n");
            default -> {
                if (isInlineElement(tagName)) {
                    String inline = normalizeInlineSpacing(renderInlineHtml(List.of(element)));
                    if (StringUtils.hasText(inline)) {
                        markdown.append(inline).append("\n\n");
                    }
                } else {
                    for (Node child : element.childNodes()) {
                        appendHtmlBlock(markdown, child, listDepth);
                    }
                }
            }
        }
    }

    private void appendHtmlParagraph(StringBuilder markdown, Element element) {
        String content = normalizeInlineSpacing(renderInlineHtml(element.childNodes()));
        if (!StringUtils.hasText(content)) {
            return;
        }

        markdown.append(content).append("\n\n");
    }

    private void appendHtmlBlockquote(StringBuilder markdown, Element element) {
        String nestedMarkdown = normalizeImportedMarkdown(convertHtmlChildrenToMarkdown(element.childNodes()));
        if (!StringUtils.hasText(nestedMarkdown)) {
            return;
        }

        for (String line : nestedMarkdown.split("\n", -1)) {
            markdown.append("> ").append(line).append('\n');
        }
        markdown.append('\n');
    }

    private void appendHtmlCodeBlock(StringBuilder markdown, Element element) {
        Element codeElement = element.selectFirst("code");
        String language = "";
        if (codeElement != null && codeElement.className() != null) {
            language = extractLanguageFromClassName(codeElement.className());
        }

        String code = codeElement != null ? codeElement.wholeText() : element.wholeText();
        markdown.append("```").append(language).append('\n')
                .append(code == null ? "" : code.trim())
                .append("\n```\n\n");
    }

    private void appendHtmlList(StringBuilder markdown, Element listElement, int depth, boolean ordered) {
        int index = 1;
        for (Element item : listElement.children()) {
            if (!"li".equalsIgnoreCase(item.tagName())) {
                continue;
            }

            String prefix = "  ".repeat(Math.max(depth, 0)) + (ordered ? index + ". " : "- ");
            String inlineContent = normalizeInlineSpacing(renderInlineHtml(extractListItemInlineNodes(item)));
            if (StringUtils.hasText(inlineContent)) {
                markdown.append(prefix).append(inlineContent).append('\n');
            } else {
                markdown.append(prefix.trim()).append('\n');
            }

            for (Element child : item.children()) {
                if ("ul".equalsIgnoreCase(child.tagName())) {
                    appendHtmlList(markdown, child, depth + 1, false);
                } else if ("ol".equalsIgnoreCase(child.tagName())) {
                    appendHtmlList(markdown, child, depth + 1, true);
                } else if ("p".equalsIgnoreCase(child.tagName())) {
                    continue;
                } else if (!isInlineElement(child.tagName().toLowerCase(Locale.ROOT))) {
                    appendHtmlBlock(markdown, child, depth + 1);
                }
            }

            index += 1;
        }

        markdown.append('\n');
    }

    private List<Node> extractListItemInlineNodes(Element item) {
        List<Node> inlineNodes = new ArrayList<>();
        for (Node child : item.childNodes()) {
            if (child instanceof Element childElement) {
                String tagName = childElement.tagName().toLowerCase(Locale.ROOT);
                if ("ul".equals(tagName) || "ol".equals(tagName)) {
                    continue;
                }
                if (!"p".equals(tagName) && !isInlineElement(tagName)) {
                    continue;
                }
            }
            inlineNodes.add(child);
        }
        return inlineNodes;
    }

    private void appendHtmlTable(StringBuilder markdown, Element table) {
        List<Element> rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }

        List<List<String>> renderedRows = new ArrayList<>();
        int maxColumns = 0;
        for (Element row : rows) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("> th, > td")) {
                cells.add(escapeMarkdownTableCell(cell.text()));
            }
            maxColumns = Math.max(maxColumns, cells.size());
            renderedRows.add(cells);
        }

        if (maxColumns == 0) {
            return;
        }

        markdown.append("| ");
        for (int column = 0; column < maxColumns; column += 1) {
            markdown.append(column < renderedRows.get(0).size() ? renderedRows.get(0).get(column) : "");
            markdown.append(" | ");
        }
        markdown.append('\n');

        markdown.append("| ");
        for (int column = 0; column < maxColumns; column += 1) {
            markdown.append("--- | ");
        }
        markdown.append('\n');

        for (int rowIndex = 1; rowIndex < renderedRows.size(); rowIndex += 1) {
            markdown.append("| ");
            List<String> row = renderedRows.get(rowIndex);
            for (int column = 0; column < maxColumns; column += 1) {
                markdown.append(column < row.size() ? row.get(column) : "");
                markdown.append(" | ");
            }
            markdown.append('\n');
        }

        markdown.append('\n');
    }

    private void appendHtmlImage(StringBuilder markdown, Element element, int depth) {
        String src = element.attr("src");
        String alt = escapeMarkdownText(element.attr("alt"));
        if (!StringUtils.hasText(src)) {
            if (StringUtils.hasText(alt)) {
                markdown.append("[").append(alt).append("]\n\n");
            }
            return;
        }

        if (depth > 0) {
            markdown.append("  ".repeat(depth));
        }
        markdown.append("![")
                .append(alt)
                .append("](")
                .append(src.trim())
                .append(")\n\n");
    }

    private String renderInlineHtml(List<Node> nodes) {
        StringBuilder inlineMarkdown = new StringBuilder();
        for (Node node : nodes) {
            appendInlineHtml(inlineMarkdown, node);
        }
        return inlineMarkdown.toString();
    }

    private void appendInlineHtml(StringBuilder inlineMarkdown, Node node) {
        if (node instanceof TextNode textNode) {
            inlineMarkdown.append(escapeMarkdownText(textNode.getWholeText().replace('\u00A0', ' ')));
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tagName = element.tagName().toLowerCase(Locale.ROOT);
        switch (tagName) {
            case "strong", "b" -> wrapInline(inlineMarkdown, element, "**", "**");
            case "em", "i" -> wrapInline(inlineMarkdown, element, "*", "*");
            case "code" -> wrapInline(inlineMarkdown, element, "`", "`", true);
            case "u" -> wrapInline(inlineMarkdown, element, "<u>", "</u>");
            case "a" -> appendInlineLink(inlineMarkdown, element);
            case "img" -> {
                String src = element.attr("src");
                String alt = escapeMarkdownText(element.attr("alt"));
                if (StringUtils.hasText(src)) {
                    inlineMarkdown.append("![")
                            .append(alt)
                            .append("](")
                            .append(src.trim())
                            .append(')');
                }
            }
            case "br" -> inlineMarkdown.append("  \n");
            default -> {
                for (Node child : element.childNodes()) {
                    appendInlineHtml(inlineMarkdown, child);
                }
            }
        }
    }

    private void appendInlineLink(StringBuilder inlineMarkdown, Element element) {
        String href = element.attr("href");
        String label = normalizeInlineSpacing(renderInlineHtml(element.childNodes()));
        if (!StringUtils.hasText(href)) {
            inlineMarkdown.append(label);
            return;
        }

        inlineMarkdown.append('[')
                .append(StringUtils.hasText(label) ? label : href.trim())
                .append("](")
                .append(href.trim())
                .append(')');
    }

    private void wrapInline(StringBuilder inlineMarkdown, Element element, String prefix, String suffix) {
        wrapInline(inlineMarkdown, element, prefix, suffix, false);
    }

    private void wrapInline(StringBuilder inlineMarkdown, Element element, String prefix, String suffix, boolean rawText) {
        String content = rawText
                ? element.text().replace("`", "\\`")
                : renderInlineHtml(element.childNodes());
        inlineMarkdown.append(prefix).append(content).append(suffix);
    }

    private boolean isInlineElement(String tagName) {
        return switch (tagName) {
            case "a", "abbr", "b", "code", "del", "em", "i", "img", "mark", "small", "span", "strong", "sub", "sup", "u" -> true;
            default -> false;
        };
    }

    private String extractLanguageFromClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return "";
        }

        for (String token : className.split("\\s+")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("language-")) {
                return normalized.substring("language-".length());
            }
            if (normalized.startsWith("lang-")) {
                return normalized.substring("lang-".length());
            }
        }

        return "";
    }

    private String renderMarkdown(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        return htmlRenderer.render(markdownParser.parse(markdown));
    }

    private String normalizeImportedMarkdown(String markdown) {
        return normalizeLineEndings(markdown).strip();
    }

    private String normalizeLineEndings(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.replace("\uFEFF", "");
        normalized = normalized.replace("\r\n", "\n");
        normalized = normalized.replace('\r', '\n');
        return normalized;
    }

    private String normalizeInlineSpacing(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private String cleanTitle(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        normalized = normalized
                .replace('\u00A0', ' ')
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("[*_`#>\\[\\]]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();

        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private String escapeMarkdownText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }

    private String escapeMarkdownTableCell(String value) {
        return normalizeInlineSpacing(value)
                .replace("|", "\\|");
    }

    private String extensionOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex < 0) {
            return "";
        }

        return fileName.substring(lastDotIndex).toLowerCase(Locale.ROOT);
    }

    private record ImportedNote(String title, String markdown, String html) {
    }

    private enum ImportFormat {
        TEXT,
        MARKDOWN,
        HTML,
        WORD,
        EXCEL;

        private static ImportFormat fromHint(String hint) {
            return switch (hint.trim().toLowerCase(Locale.ROOT)) {
                case "text", "txt" -> TEXT;
                case "markdown", "md" -> MARKDOWN;
                case "html", "htm" -> HTML;
                case "word", "doc", "docx" -> WORD;
                case "excel", "xls", "xlsx" -> EXCEL;
                default -> throw new IllegalArgumentException("不支持的导入格式");
            };
        }

        private static ImportFormat fromExtension(String extension) {
            return switch (extension.trim().toLowerCase(Locale.ROOT)) {
                case ".txt" -> TEXT;
                case ".md", ".markdown" -> MARKDOWN;
                case ".html", ".htm" -> HTML;
                case ".doc", ".docx" -> WORD;
                case ".xls", ".xlsx" -> EXCEL;
                default -> throw new IllegalArgumentException("仅支持导入 Markdown、HTML 或 Word（.docx）文件");
            };
        }

        private static ImportFormat fromContentType(String contentType) {
            return switch (contentType.trim().toLowerCase(Locale.ROOT)) {
                case "text/plain" -> TEXT;
                case "text/markdown" -> MARKDOWN;
                case "text/html" -> HTML;
                case "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> WORD;
                case "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> EXCEL;
                default -> throw new IllegalArgumentException("无法根据文件类型识别导入格式");
            };
        }
    }
}
