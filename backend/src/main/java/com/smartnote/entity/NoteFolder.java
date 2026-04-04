package com.smartnote.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 笔记文件夹实体 (NoteFolder)
 *
 * 在 Notebook（知识库）内部提供一层目录分组，形成：
 *   Notebook → NoteFolder → Note 的三层结构。
 *
 * 支持自引用嵌套（parentFolder），但当前 UI 只暴露单层。
 * JPA 的 ddl-auto=update 会自动建表，无需手写 DDL。
 */
@Entity
@Table(name = "note_folders")
@Data
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class NoteFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属笔记本
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notebook_id", nullable = false)
    @JsonIgnoreProperties({"user", "notes", "hibernateLazyInitializer", "handler"})
    private Notebook notebook;

    /**
     * 父文件夹（为 null 表示顶层文件夹）
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    @JsonIgnoreProperties({"notebook", "parentFolder", "hibernateLazyInitializer", "handler"})
    private NoteFolder parentFolder;

    /**
     * 文件夹名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 排序权重（数字越小排越靠前）
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
