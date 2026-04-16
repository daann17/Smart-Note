package com.smartnote.dto;

import lombok.Data;

/**
 * 笔记请求 DTO
 */
@Data
public class NoteRequest {
    private Long notebookId;
    private Long folderId;
    private String title;
    private String content;
    private String contentHtml;
    private String status;
    private java.util.List<String> tags;
    private Boolean forceHistory; // 是否强制生成历史版本
}
