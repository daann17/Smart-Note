package com.smartnote.dto;

import java.util.List;

public record KnowledgeGraphResponse(
        Summary summary,
        List<Node> nodes,
        List<Link> links
) {
    public record Summary(
            int notebookCount,
            int noteCount,
            int tagCount,
            int relationCount,
            int relatedNoteCount
    ) {
    }

    public record Node(
            String id,
            String label,
            String type,
            int size,
            String description,
            String path,
            Long entityId,
            String notebookName,
            String updatedAt
    ) {
    }

    public record Link(
            String source,
            String target,
            String type,
            int weight,
            String label
    ) {
    }
}
