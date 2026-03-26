package com.smartnote.dto;

import lombok.Data;

import java.util.List;

@Data
public class AIChatRequest {
    private String message;
    private Long currentNoteId; // Optional, if user is viewing a specific note
    private List<AIChatMessageRequest> history;
}
