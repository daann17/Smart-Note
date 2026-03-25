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
 * 笔记本实体类 (Notebook)
 * 代表用户的知识库文件夹
 */
@Entity
@Table(name = "notebooks")
@Data
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Notebook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属用户
     * 关联到 User 表
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"password", "role", "notebooks"})
    private User user;

    /**
     * 笔记本名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 描述信息
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 封面图片 URL
     */
    @Column(name = "cover_url")
    private String coverUrl;

    /**
     * 是否公开
     * 默认为私有
     */
    @Column(name = "is_public")
    private boolean isPublic = false;

    /**
     * 状态 (NORMAL, TRASH)
     */
    @Column(length = 20)
    private String status = "NORMAL";

    /**
     * 创建时间
     * 自动填充
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     * 自动更新
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
