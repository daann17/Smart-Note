package com.smartnote.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminOverviewResponse {
    private long totalUsers;
    private long activeUsers;
    private long disabledUsers;
    private long adminUsers;
    private long totalNotes;
    private long totalNotebooks;
    private long totalTags;
}
