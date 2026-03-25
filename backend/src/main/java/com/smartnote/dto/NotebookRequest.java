package com.smartnote.dto;

import lombok.Data;

/**
 * 创建笔记本请求 DTO
 */
@Data
public class NotebookRequest {
    
    /**
     * 笔记本名称
     */
    private String name;
    
    /**
     * 描述
     */
    private String description;
    
    /**
     * 是否公开
     */
    private Boolean isPublic;
}
