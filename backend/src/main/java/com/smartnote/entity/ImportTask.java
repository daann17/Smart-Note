package com.smartnote.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "import_tasks")
public class ImportTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "notebook_id", nullable = false)
    private Notebook notebook;

    private String fileName;
    private Long fileSize;
    private int totalFiles;
    private int successCount;
    private int failureCount;

    @Enumerated(EnumType.STRING)
    private ImportStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public enum ImportStatus {
        PENDING,
        PROCESSING,
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }
}