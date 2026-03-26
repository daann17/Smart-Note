package com.smartnote.dto;

public record AdminSearchMaintenanceResponse(
        String searchStrategy,
        long searchableNotes,
        long searchableNotebooks,
        long searchableTags,
        long noteTagLinks,
        AdminSearchMaintenanceLastRunResponse lastRun
) {
}
