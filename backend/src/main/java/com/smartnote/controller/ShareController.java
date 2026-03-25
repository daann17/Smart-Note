package com.smartnote.controller;

import com.smartnote.dto.ShareCommentResponse;
import com.smartnote.entity.NoteComment;
import com.smartnote.entity.NoteShare;
import com.smartnote.service.ShareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/shares")
public class ShareController {

    @Autowired
    private ShareService shareService;

    @GetMapping
    public ResponseEntity<?> getUserShares() {
        List<NoteShare> shares = shareService.getUserShares();
        return ResponseEntity.ok(shares);
    }

    @PostMapping("/note/{noteId}")
    public ResponseEntity<?> createShare(@PathVariable Long noteId, @RequestBody(required = false) Map<String, Object> payload) {
        Integer expireDays = null;
        String extractionCode = null;
        Boolean allowComment = false;
        Boolean allowEdit = false;
        if (payload != null) {
            if (payload.containsKey("expireDays") && payload.get("expireDays") != null) {
                Object expireDaysValue = payload.get("expireDays");
                if (expireDaysValue instanceof Integer) {
                    expireDays = (Integer) expireDaysValue;
                } else if (expireDaysValue instanceof String) {
                    try {
                        expireDays = Integer.parseInt((String) expireDaysValue);
                    } catch (NumberFormatException ignored) {
                        // Ignore invalid expireDays and fall back to permanent share.
                    }
                }
            }
            if (payload.containsKey("extractionCode")) {
                extractionCode = (String) payload.get("extractionCode");
            }
            if (payload.containsKey("allowComment")) {
                Object allowCommentValue = payload.get("allowComment");
                if (allowCommentValue instanceof Boolean) {
                    allowComment = (Boolean) allowCommentValue;
                } else if (allowCommentValue instanceof String) {
                    allowComment = Boolean.parseBoolean((String) allowCommentValue);
                }
            }
            if (payload.containsKey("allowEdit")) {
                Object allowEditValue = payload.get("allowEdit");
                if (allowEditValue instanceof Boolean) {
                    allowEdit = (Boolean) allowEditValue;
                } else if (allowEditValue instanceof String) {
                    allowEdit = Boolean.parseBoolean((String) allowEditValue);
                }
            }
        }
        NoteShare share = shareService.createOrUpdateShare(noteId, expireDays, extractionCode, allowComment, allowEdit);
        return ResponseEntity.ok(share);
    }

    @GetMapping("/note/{noteId}")
    public ResponseEntity<?> getShare(@PathVariable Long noteId) {
        NoteShare share = shareService.getShareByNoteId(noteId);
        if (share == null) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok(share);
    }

    @GetMapping("/note/{noteId}/comments")
    public ResponseEntity<?> getShareComments(@PathVariable Long noteId) {
        List<ShareCommentResponse> comments = shareService.getShareCommentsByNoteId(noteId)
                .stream()
                .map(this::toCommentResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(comments);
    }

    @DeleteMapping("/note/{noteId}")
    public ResponseEntity<?> disableShare(@PathVariable Long noteId) {
        shareService.disableShare(noteId);
        return ResponseEntity.ok(Map.of("message", "分享已关闭"));
    }

    private ShareCommentResponse toCommentResponse(NoteComment comment) {
        return new ShareCommentResponse(
                comment.getId(),
                comment.getContent(),
                comment.getAuthorName(),
                comment.getAnchorKey(),
                comment.getAnchorType(),
                comment.getAnchorLabel(),
                comment.getAnchorPreview(),
                comment.getCreatedAt()
        );
    }
}
