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
 * 笔记实体类 (Note)
 */
@Entity
@Table(name = "notes")
@Data
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Note {

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
     * 笔记标题
     */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * Markdown 内容
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 渲染后的 HTML 内容 (用于展示和搜索)
     */
    @Column(name = "content_html", columnDefinition = "TEXT")
    private String contentHtml;

    /**
     * 摘要 (AI 生成)
     */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /**
     * 状态 (DRAFT, PUBLISHED, TRASH)
     */
    @Column(length = 20)
    private String status = "DRAFT";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "note_tags",
        joinColumns = @JoinColumn(name = "note_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @JsonIgnoreProperties({"user"})
    private java.util.Set<Tag> tags = new java.util.HashSet<>();
}
