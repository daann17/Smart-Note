package com.smartnote.controller;

import com.smartnote.dto.ShareCommentRequest;
import com.smartnote.dto.ShareCommentResponse;
import com.smartnote.entity.Note;
import com.smartnote.entity.NoteComment;
import com.smartnote.entity.NoteShare;
import com.smartnote.repository.NoteCommentRepository;
import com.smartnote.repository.NoteRepository;
import com.smartnote.service.ShareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public/shares")
public class PublicShareController {

    @Autowired
    private ShareService shareService;

    @Autowired
    private NoteCommentRepository commentRepository;

    @Autowired
    private NoteRepository noteRepository;

    @GetMapping("/{token}/info")
    public ResponseEntity<?> getShareInfo(@PathVariable String token) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            boolean requireCode = share.getExtractionCode() != null && !share.getExtractionCode().isEmpty();

            Map<String, Object> response = new HashMap<>();
            response.put("requireCode", requireCode);
            if (!requireCode) {
                Note note = share.getNote();
                response.put("title", note.getTitle());
                response.put("author", note.getNotebook().getUser().getUsername());
                response.put("allowComment", share.getAllowComment());
                response.put("allowEdit", share.getAllowEdit());
            }
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> getSharedNoteWithCode(
            @PathVariable String token,
            @RequestBody(required = false) Map<String, String> payload
    ) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateExtractionCode(share, payload != null ? payload.get("code") : null);

            Note note = share.getNote();
            Map<String, Object> response = new HashMap<>();
            response.put("noteId", note.getId());
            response.put("title", note.getTitle());
            response.put("content", note.getContent());
            response.put("contentHtml", note.getContentHtml());
            response.put("summary", note.getSummary());
            response.put("updatedAt", note.getUpdatedAt());
            response.put("author", note.getNotebook().getUser().getUsername());
            response.put("allowComment", share.getAllowComment());
            response.put("allowEdit", share.getAllowEdit());
            response.put("shareId", share.getId());

            return ResponseEntity.ok(response);
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PutMapping("/{token}")
    public ResponseEntity<?> updateSharedNote(@PathVariable String token, @RequestBody Map<String, Object> payload) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            if (share.getAllowEdit() == null || !share.getAllowEdit()) {
                return ResponseEntity.status(403).body(Map.of("message", "该分享未开启协同编辑功能"));
            }

            validateExtractionCode(share, payload.containsKey("code") ? (String) payload.get("code") : null);

            Note note = share.getNote();
            if (payload.containsKey("content")) {
                note.setContent((String) payload.get("content"));
            }
            if (payload.containsKey("contentHtml")) {
                note.setContentHtml((String) payload.get("contentHtml"));
            }

            if (payload.containsKey("content")) {
                note.setUpdatedAt(LocalDateTime.now());
                noteRepository.save(note);
            }

            return ResponseEntity.ok(Map.of("message", "保存成功", "updatedAt", note.getUpdatedAt()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/{token}/comments")
    public ResponseEntity<?> getComments(
            @PathVariable String token,
            @RequestParam(required = false) String code
    ) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateCommentAccess(share, code);

            List<ShareCommentResponse> comments = commentRepository.findByShareIdOrderByCreatedAtDesc(share.getId())
                    .stream()
                    .map(this::toCommentResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(comments);
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{token}/comments")
    public ResponseEntity<?> addComment(@PathVariable String token, @RequestBody ShareCommentRequest request) {
        try {
            NoteShare share = shareService.getShareByToken(token);
            validateCommentAccess(share, request.getCode());

            NoteComment comment = new NoteComment();
            comment.setShareId(share.getId());
            comment.setContent(normalizeRequiredText(request.getContent(), 1000, "评论内容"));
            comment.setAuthorName(normalizeOptionalText(request.getAuthorName(), 50, "作者名称", "匿名用户"));
            comment.setAnchorKey(normalizeOptionalText(request.getAnchorKey(), 120, "段落锚点", null));
            comment.setAnchorType(normalizeOptionalText(request.getAnchorType(), 20, "段落类型", null));
            comment.setAnchorLabel(normalizeOptionalText(request.getAnchorLabel(), 120, "段落标题", null));
            comment.setAnchorPreview(normalizeOptionalText(request.getAnchorPreview(), 300, "段落摘要", null));

            NoteComment savedComment = commentRepository.save(comment);
            return ResponseEntity.ok(toCommentResponse(savedComment));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    private void validateCommentAccess(NoteShare share, String code) {
        if (share.getAllowComment() == null || !share.getAllowComment()) {
            throw new IllegalStateException("该分享未开启评论功能");
        }

        validateExtractionCode(share, code);
    }

    private void validateExtractionCode(NoteShare share, String code) {
        String extractionCode = share.getExtractionCode();
        if (extractionCode != null && !extractionCode.isBlank()) {
            if (code == null || !extractionCode.equals(code.trim())) {
                throw new IllegalStateException("提取码错误");
            }
        }
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

    private String normalizeRequiredText(String value, int maxLength, String fieldName) {
        String normalized = normalizeOptionalText(value, maxLength, fieldName, null);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength, String fieldName, String fallback) {
        if (value == null) {
            return fallback;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }

        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "长度不能超过" + maxLength + "个字符");
        }

        return trimmed;
    }
}
