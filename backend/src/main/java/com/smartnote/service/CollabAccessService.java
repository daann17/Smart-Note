package com.smartnote.service;

import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NoteShareRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CollabAccessService {

    private final NoteRepository noteRepository;
    private final NoteShareRepository noteShareRepository;

    public CollabAccessService(NoteRepository noteRepository, NoteShareRepository noteShareRepository) {
        this.noteRepository = noteRepository;
        this.noteShareRepository = noteShareRepository;
    }

    public boolean canAccess(String username, Long noteId, String shareToken) {
        if (username == null || username.isBlank() || noteId == null) {
            return false;
        }

        if (noteRepository.existsByIdAndNotebookUserUsername(noteId, username)) {
            return true;
        }

        if (shareToken == null || shareToken.isBlank()) {
            return false;
        }

        return noteShareRepository.existsActiveEditableShareForNote(shareToken, noteId, LocalDateTime.now());
    }
}
