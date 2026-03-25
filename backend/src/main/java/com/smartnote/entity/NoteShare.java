package com.smartnote.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "note_shares")
@Data
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class NoteShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    @JsonIgnoreProperties({"notebook", "content", "contentHtml", "summary", "tags"})
    private Note note;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "extraction_code", length = 20)
    private String extractionCode;

    @Column(name = "allow_comment")
    private Boolean allowComment = false;

    @Column(name = "allow_edit")
    private Boolean allowEdit = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
