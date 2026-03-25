package com.smartnote.controller;

import com.smartnote.dto.KnowledgeGraphResponse;
import com.smartnote.service.KnowledgeGraphService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-graph")
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;

    public KnowledgeGraphController(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @GetMapping
    public ResponseEntity<KnowledgeGraphResponse> getKnowledgeGraph(Authentication authentication) {
        return ResponseEntity.ok(knowledgeGraphService.getKnowledgeGraph(authentication.getName()));
    }
}
