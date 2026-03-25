package com.smartnote.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ShareCommentResponse {
    private Long id;
    private String content;
    private String authorName;
    private String anchorKey;
    private String anchorType;
    private String anchorLabel;
    private String anchorPreview;
    private LocalDateTime createdAt;
}
