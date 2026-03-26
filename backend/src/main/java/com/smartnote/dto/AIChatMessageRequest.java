package com.smartnote.dto;

import lombok.Data;

@Data
public class AIChatMessageRequest {
    private String role;
    private String content;
}
