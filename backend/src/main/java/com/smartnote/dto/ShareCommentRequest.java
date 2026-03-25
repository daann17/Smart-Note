package com.smartnote.dto;

import lombok.Data;

@Data
public class ShareCommentRequest {
    private String content;
    private String authorName;
    private String code;
    private String anchorKey;
    private String anchorType;
    private String anchorLabel;
    private String anchorPreview;
}
