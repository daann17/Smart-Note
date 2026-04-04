package com.smartnote.service;

import com.smartnote.dto.NoteFolderRequest;
import com.smartnote.entity.NoteFolder;
import com.smartnote.entity.Notebook;
import com.smartnote.repository.NoteFolderRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NoteFolderService {

    @Autowired
    private NoteFolderRepository folderRepository;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NotebookRepository notebookRepository;

    public List<NoteFolder> getFoldersByNotebook(Long notebookId) {
        return folderRepository.findByNotebookIdOrderBySortOrder(notebookId);
    }

    @Transactional
    public NoteFolder createFolder(Long notebookId, NoteFolderRequest request) {
        Notebook notebook = notebookRepository.findById(notebookId)
                .orElseThrow(() -> new IllegalArgumentException("笔记本不存在: " + notebookId));

        NoteFolder folder = new NoteFolder();
        folder.setNotebook(notebook);
        folder.setName(request.getName().trim());

        if (request.getParentFolderId() != null) {
            NoteFolder parent = getFolderOrThrow(request.getParentFolderId(), "父文件夹不存在: " + request.getParentFolderId());
            validateFolderNotebook(parent, notebookId, "父文件夹不属于当前笔记本");
            folder.setParentFolder(parent);
        }

        int maxOrder = folderRepository.findMaxSortOrderByNotebookId(notebookId);
        folder.setSortOrder(maxOrder + 1);

        return folderRepository.save(folder);
    }

    @Transactional
    public NoteFolder renameFolder(Long folderId, String newName) {
        NoteFolder folder = getFolderOrThrow(folderId, "文件夹不存在: " + folderId);
        folder.setName(newName.trim());
        return folderRepository.save(folder);
    }

    @Transactional
    public void deleteFolder(Long folderId) {
        NoteFolder folder = getFolderOrThrow(folderId, "文件夹不存在: " + folderId);

        noteRepository.findByFolderId(folderId).forEach(note -> {
            note.setFolder(null);
            noteRepository.save(note);
        });

        folderRepository.findByParentFolderId(folderId).forEach(child -> deleteFolder(child.getId()));
        folderRepository.delete(folder);
    }

    @Transactional
    public void moveNoteToFolder(Long noteId, Long folderId) {
        var note = noteRepository.findById(noteId)
                .orElseThrow(() -> new IllegalArgumentException("笔记不存在: " + noteId));

        if (folderId == null) {
            note.setFolder(null);
            noteRepository.save(note);
            return;
        }

        NoteFolder folder = getFolderOrThrow(folderId, "文件夹不存在: " + folderId);
        validateFolderNotebook(folder, note.getNotebook().getId(), "目标文件夹不属于当前笔记所在的笔记本");

        note.setFolder(folder);
        noteRepository.save(note);
    }

    private NoteFolder getFolderOrThrow(Long folderId, String message) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new IllegalArgumentException(message));
    }

    private void validateFolderNotebook(NoteFolder folder, Long notebookId, String message) {
        Long folderNotebookId = folder.getNotebook() == null ? null : folder.getNotebook().getId();
        if (folderNotebookId == null || !folderNotebookId.equals(notebookId)) {
            throw new IllegalArgumentException(message);
        }
    }
}
