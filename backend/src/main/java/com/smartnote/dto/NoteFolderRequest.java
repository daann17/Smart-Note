package com.smartnote.dto;

/**
 * 创建 / 重命名文件夹的请求体
 */
public class NoteFolderRequest {

    /** 文件夹名称 */
    private String name;

    /** 父文件夹 ID（为 null 时创建顶层文件夹） */
    private Long parentFolderId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getParentFolderId() { return parentFolderId; }
    public void setParentFolderId(Long parentFolderId) { this.parentFolderId = parentFolderId; }
}
