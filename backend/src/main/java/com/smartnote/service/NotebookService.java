package com.smartnote.service;

import com.smartnote.dto.NotebookRequest;
import com.smartnote.entity.Notebook;
import com.smartnote.entity.User;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.UserRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.entity.Note;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 笔记本业务逻辑层
 */
@Service
public class NotebookService {

    @Autowired
    private NotebookRepository notebookRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteService noteService;

    /**
     * 获取当前登录用户
     */
    private User getCurrentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String username;
        if (principal instanceof UserDetails) {
            username = ((UserDetails) principal).getUsername();
        } else {
            username = principal.toString();
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /**
     * 创建笔记本
     */
    @Transactional
    public Notebook createNotebook(NotebookRequest request) {
        User user = getCurrentUser();

        Notebook notebook = new Notebook();
        notebook.setUser(user);
        notebook.setName(request.getName());
        notebook.setDescription(request.getDescription());
        notebook.setPublic(request.getIsPublic() != null ? request.getIsPublic() : false);
        notebook.setStatus("NORMAL"); // 显式设置默认状态

        return notebookRepository.save(notebook);
    }

    /**
     * 获取当前用户的所有笔记本
     */
    public List<Notebook> getMyNotebooks() {
        User user = getCurrentUser();
        return notebookRepository.findByUserIdAndStatusNotOrderByCreatedAtDesc(user.getId(), "TRASH");
    }

    /**
     * 更新笔记本
     */
    @Transactional
    public Notebook updateNotebook(Long id, NotebookRequest request) {
        Notebook notebook = notebookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Notebook not found"));
                
        if (!notebook.getUser().getId().equals(getCurrentUser().getId())) {
            throw new RuntimeException("No permission to update this notebook");
        }

        if (request.getName() != null) notebook.setName(request.getName());
        if (request.getDescription() != null) notebook.setDescription(request.getDescription());
        if (request.getIsPublic() != null) notebook.setPublic(request.getIsPublic());

        return notebookRepository.save(notebook);
    }

    /**
     * 删除笔记本 (连带其中的笔记一起软删除)
     */
    @Transactional
    public void deleteNotebook(Long id) {
        Notebook notebook = notebookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Notebook not found"));

        if (!notebook.getUser().getId().equals(getCurrentUser().getId())) {
            throw new RuntimeException("No permission to delete this notebook");
        }

        // 1. 将笔记本本身标记为 TRASH
        notebook.setStatus("TRASH");
        notebookRepository.save(notebook);

        // 2. 将笔记本下的所有笔记标记为 TRASH
        List<Note> allNotes = noteRepository.findByNotebookId(id);
        for (Note note : allNotes) {
            if (!"TRASH".equals(note.getStatus())) {
                noteService.deleteNote(note.getId());
            }
        }
    }
}