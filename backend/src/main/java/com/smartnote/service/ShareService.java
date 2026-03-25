package com.smartnote.service;

import com.smartnote.entity.Note;
import com.smartnote.entity.NoteComment;
import com.smartnote.entity.NoteShare;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteCommentRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NoteShareRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ShareService {

    @Autowired
    private NoteShareRepository shareRepository;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteCommentRepository commentRepository;

    @Autowired
    private UserRepository userRepository;

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

    private Note getOwnedNote(Long noteId) {
        User currentUser = getCurrentUser();
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Note not found"));

        Long ownerId = note.getNotebook().getUser().getId();
        if (!ownerId.equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "No permission to access this note");
        }

        return note;
    }

    @Transactional
    public NoteShare createOrUpdateShare(Long noteId, Integer expireDays, String extractionCode, Boolean allowComment, Boolean allowEdit) {
        Note note = getOwnedNote(noteId);

        Optional<NoteShare> existingShare = shareRepository.findByNoteId(noteId);
        NoteShare share;
        if (existingShare.isPresent()) {
            share = existingShare.get();
        } else {
            share = new NoteShare();
            share.setNote(note);
            share.setToken(UUID.randomUUID().toString().replace("-", ""));
        }

        if (expireDays != null && expireDays > 0) {
            share.setExpireAt(LocalDateTime.now().plusDays(expireDays));
        } else {
            share.setExpireAt(null); // 永不过期
        }
        
        share.setExtractionCode(extractionCode != null && !extractionCode.trim().isEmpty() ? extractionCode.trim() : null);
        share.setAllowComment(allowComment != null ? allowComment : false);
        share.setAllowEdit(allowEdit != null ? allowEdit : false);
        share.setIsActive(true);

        return shareRepository.save(share);
    }

    @Transactional(readOnly = true)
    public NoteShare getShareByNoteId(Long noteId) {
        getOwnedNote(noteId);
        return shareRepository.findByNoteId(noteId).orElse(null);
    }

    @Transactional
    public void disableShare(Long noteId) {
        getOwnedNote(noteId);
        shareRepository.findByNoteId(noteId).ifPresent(share -> {
            share.setIsActive(false);
            shareRepository.save(share);
        });
    }

    @Transactional(readOnly = true)
    public List<NoteComment> getShareCommentsByNoteId(Long noteId) {
        getOwnedNote(noteId);

        Optional<NoteShare> share = shareRepository.findByNoteId(noteId);
        if (share.isEmpty()) {
            return List.of();
        }

        return commentRepository.findByShareIdOrderByCreatedAtDesc(share.get().getId());
    }

    @Transactional(readOnly = true)
    public NoteShare getShareByToken(String token) {
        NoteShare share = shareRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("分享链接不存在"));
        
        if (!share.getIsActive()) {
            throw new RuntimeException("该分享链接已被关闭");
        }
        
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("该分享链接已过期");
        }
        
        // 触发懒加载，确保后续能访问到用户名
        share.getNote().getNotebook().getUser().getUsername();
        
        return share;
    }

    @Transactional(readOnly = true)
    public java.util.List<NoteShare> getUserShares() {
        return shareRepository.findByUserId(getCurrentUser().getId());
    }
}
