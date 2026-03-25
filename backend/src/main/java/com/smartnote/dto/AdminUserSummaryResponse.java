package com.smartnote.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AdminUserSummaryResponse {
    private Long id;
    private String username;
    private String email;
    private String nickname;
    private String role;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long noteCount;
    private long notebookCount;
    private long tagCount;
}
