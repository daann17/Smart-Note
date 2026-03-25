package com.smartnote.controller;

import com.smartnote.entity.Tag;
import com.smartnote.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
public class TagController {

    @Autowired
    private TagService tagService;

    @GetMapping
    public ResponseEntity<List<Tag>> getAllTags(Authentication authentication) {
        return ResponseEntity.ok(tagService.getUserTags(authentication.getName()));
    }

    @PostMapping
    public ResponseEntity<Tag> createTag(Authentication authentication, @RequestBody Map<String, String> payload) {
        String tagName = payload.get("name");
        if (tagName == null || tagName.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tagService.createTag(authentication.getName(), tagName));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(Authentication authentication, @PathVariable Long id) {
        tagService.deleteTag(authentication.getName(), id);
        return ResponseEntity.ok().build();
    }
}
