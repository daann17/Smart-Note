package com.smartnote.controller;

import com.smartnote.dto.NoteFolderRequest;
import com.smartnote.entity.NoteFolder;
import com.smartnote.service.NoteFolderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class NoteFolderController {

    @Autowired
    private NoteFolderService folderService;

    @GetMapping("/api/notebooks/{notebookId}/folders")
    public ResponseEntity<List<NoteFolder>> listFolders(@PathVariable Long notebookId) {
        return ResponseEntity.ok(folderService.getFoldersByNotebook(notebookId));
    }

    @PostMapping("/api/notebooks/{notebookId}/folders")
    public ResponseEntity<?> createFolder(
            @PathVariable Long notebookId,
            @RequestBody NoteFolderRequest request
    ) {
        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件夹名称不能为空"));
        }

        try {
            return ResponseEntity.ok(folderService.createFolder(notebookId, request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PutMapping("/api/folders/{folderId}")
    public ResponseEntity<?> renameFolder(
            @PathVariable Long folderId,
            @RequestBody Map<String, String> body
    ) {
        String newName = body.get("name");
        if (newName == null || newName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件夹名称不能为空"));
        }

        try {
            return ResponseEntity.ok(folderService.renameFolder(folderId, newName));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @DeleteMapping("/api/folders/{folderId}")
    public ResponseEntity<?> deleteFolder(@PathVariable Long folderId) {
        try {
            folderService.deleteFolder(folderId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/api/notes/{noteId}/move-to-folder")
    public ResponseEntity<?> moveNoteToFolder(
            @PathVariable Long noteId,
            @RequestParam(required = false) Long folderId
    ) {
        try {
            folderService.moveNoteToFolder(noteId, folderId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
