package com.smartnote.controller;

import com.smartnote.dto.NoteRequest;
import com.smartnote.entity.Note;
import com.smartnote.entity.NoteHistory;
import com.smartnote.service.AIService;
import com.smartnote.service.NoteExportService;
import com.smartnote.service.NoteImportService;
import com.smartnote.service.NoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    @Autowired
    private NoteService noteService;

    @Autowired
    private AIService aiService;

    @Autowired
    private NoteExportService noteExportService;

    @Autowired
    private NoteImportService noteImportService;

    @GetMapping("/search")
    public ResponseEntity<?> searchNotes(
            Authentication authentication,
            @RequestParam String q,
            @RequestParam(required = false) Long notebookId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        try {
            return ResponseEntity.ok(noteService.searchNotes(
                    authentication.getName(),
                    q.trim(),
                    notebookId,
                    tag,
                    startDate,
                    endDate
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<Note> createNote(@RequestBody NoteRequest request) {
        return ResponseEntity.ok(noteService.createNote(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Note> updateNote(@PathVariable Long id, @RequestBody NoteRequest request) {
        return ResponseEntity.ok(noteService.updateNote(id, request));
    }

    @GetMapping("/notebook/{notebookId}")
    public ResponseEntity<List<Note>> getNotesByNotebook(@PathVariable Long notebookId) {
        return ResponseEntity.ok(noteService.getNotesByNotebook(notebookId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Note> getNoteById(@PathVariable Long id) {
        return ResponseEntity.ok(noteService.getNoteById(id));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<NoteHistory>> getNoteHistories(@PathVariable Long id) {
        return ResponseEntity.ok(noteService.getNoteHistories(id));
    }

    @GetMapping("/history/{historyId}")
    public ResponseEntity<NoteHistory> getNoteHistoryById(@PathVariable Long historyId) {
        return ResponseEntity.ok(noteService.getNoteHistoryById(historyId));
    }

    @PostMapping("/{id}/rollback/{historyId}")
    public ResponseEntity<Note> rollbackToHistory(@PathVariable Long id, @PathVariable Long historyId) {
        return ResponseEntity.ok(noteService.rollbackToHistory(id, historyId));
    }

    @PostMapping("/{id}/summary")
    public ResponseEntity<Note> generateSummary(@PathVariable Long id) {
        return ResponseEntity.ok(aiService.generateSummary(id));
    }

    @GetMapping("/{id}/suggest-tags")
    public ResponseEntity<List<String>> suggestTags(@PathVariable Long id) {
        return ResponseEntity.ok(aiService.suggestTags(id));
    }

    @PostMapping("/{id}/move")
    public ResponseEntity<Note> moveNote(@PathVariable Long id, @RequestParam Long targetNotebookId) {
        return ResponseEntity.ok(noteService.moveNote(id, targetNotebookId));
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<Note> copyNote(@PathVariable Long id, @RequestParam Long targetNotebookId) {
        return ResponseEntity.ok(noteService.copyNote(id, targetNotebookId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long id) {
        noteService.deleteNote(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/hard")
    public ResponseEntity<Void> hardDeleteNote(@PathVariable Long id) {
        noteService.hardDeleteNote(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/trash")
    public ResponseEntity<List<Note>> getTrashNotes(Authentication authentication) {
        return ResponseEntity.ok(noteService.getTrashNotes(authentication.getName()));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Note> restoreNote(@PathVariable Long id) {
        return ResponseEntity.ok(noteService.restoreNote(id));
    }

    @DeleteMapping("/trash/empty")
    public ResponseEntity<Void> emptyTrash(Authentication authentication) {
        noteService.emptyTrash(authentication.getName());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/recent")
    public ResponseEntity<List<Note>> getRecentNotes(Authentication authentication) {
        return ResponseEntity.ok(noteService.getRecentNotes(authentication.getName()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importNote(
            @RequestParam Long notebookId,
            @RequestParam(required = false) Long folderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String format
    ) {
        try {
            NoteRequest importRequest = noteImportService.buildImportRequest(notebookId, file, format);
            importRequest.setFolderId(folderId);
            return ResponseEntity.ok(noteService.createNote(importRequest));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/{id}/export/markdown")
    public ResponseEntity<byte[]> exportToMarkdown(@PathVariable Long id) {
        Note note = noteService.getNoteById(id);
        return buildDownloadResponse(
                noteExportService.exportMarkdown(note),
                note.getTitle(),
                "md",
                MediaType.parseMediaType("text/markdown;charset=UTF-8")
        );
    }

    @GetMapping("/{id}/export/html")
    public ResponseEntity<byte[]> exportToHtml(@PathVariable Long id) {
        Note note = noteService.getNoteById(id);
        return buildDownloadResponse(
                noteExportService.exportHtml(note),
                note.getTitle(),
                "html",
                MediaType.parseMediaType("text/html;charset=UTF-8")
        );
    }

    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportToPdf(@PathVariable Long id) {
        Note note = noteService.getNoteById(id);
        return buildDownloadResponse(
                noteExportService.exportPdf(note),
                note.getTitle(),
                "pdf",
                MediaType.APPLICATION_PDF
        );
    }

    @GetMapping("/{id}/export/word")
    public ResponseEntity<byte[]> exportToWord(@PathVariable Long id) {
        Note note = noteService.getNoteById(id);
        return buildDownloadResponse(
                noteExportService.exportWord(note),
                note.getTitle(),
                "docx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        );
    }

    private ResponseEntity<byte[]> buildDownloadResponse(byte[] bytes, String title, String extension, MediaType mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);

        String safeTitle = title == null || title.trim().isEmpty() ? "untitled-note" : title.trim();
        String fileName = URLEncoder.encode(safeTitle + "." + extension, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        headers.setContentDispositionFormData("attachment", fileName);

        return ResponseEntity.ok()
                .headers(headers)
                .body(bytes);
    }
}
