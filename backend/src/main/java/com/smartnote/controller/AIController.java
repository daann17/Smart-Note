package com.smartnote.controller;

import com.smartnote.dto.AIChatRequest;
import com.smartnote.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    @Autowired
    private AIService aiService;

    /**
     * 流式 AI 对话接口
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody AIChatRequest request, Authentication authentication) {
        String username = authentication.getName();
        return aiService.chat(request.getMessage(), request.getCurrentNoteId(), username);
    }
}
