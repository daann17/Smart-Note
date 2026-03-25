package com.smartnote.controller;

import com.smartnote.entity.User;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.TagRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NotebookRepository notebookRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverviewStats() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Map<String, Object> stats = new HashMap<>();
        
        // Personal stats
        long totalNotes = noteRepository.countByNotebookUserIdAndStatusNot(user.getId(), "TRASH");
        long totalNotebooks = notebookRepository.countByUserIdAndStatusNot(user.getId(), "TRASH");
        long totalTags = tagRepository.countByUserId(user.getId());
        
        stats.put("totalNotes", totalNotes);
        stats.put("totalNotebooks", totalNotebooks);
        stats.put("totalTags", totalTags);

        // System stats if admin
        if ("ADMIN".equals(user.getRole())) {
            stats.put("sysTotalUsers", userRepository.count());
            stats.put("sysTotalNotes", noteRepository.count());
            stats.put("sysTotalNotebooks", notebookRepository.count());
            stats.put("sysTotalTags", tagRepository.count());
        }

        return ResponseEntity.ok(stats);
    }
}