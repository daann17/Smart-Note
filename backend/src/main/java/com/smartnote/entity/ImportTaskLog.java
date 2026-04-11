package com.smartnote.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "import_task_logs")
public class ImportTaskLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private ImportTask task;

    private String filePath;
    private String fileName;
    private boolean success;
    private String errorMessage;
    private Long importedNoteId;
}