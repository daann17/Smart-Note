package com.smartnote.dto;

import java.time.LocalDateTime;

public record AdminSearchMaintenanceLastRunResponse(
        String status,
        String operation,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String message
) {
}
