package com.smartnote.controller;

import com.smartnote.dto.NotebookRequest;
import com.smartnote.entity.Notebook;
import com.smartnote.service.NotebookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 笔记本接口控制器
 */
@RestController
@RequestMapping("/api/notebooks")
public class NotebookController {

    @Autowired
    private NotebookService notebookService;

    /**
     * 创建笔记本接口
     * POST /api/notebooks
     */
    @PostMapping
    public ResponseEntity<Notebook> createNotebook(@RequestBody NotebookRequest request) {
        Notebook notebook = notebookService.createNotebook(request);
        return ResponseEntity.ok(notebook);
    }

    /**
     * 获取当前用户的笔记本列表接口
     * GET /api/notebooks
     */
    @GetMapping
    public ResponseEntity<List<Notebook>> getMyNotebooks() {
        List<Notebook> notebooks = notebookService.getMyNotebooks();
        return ResponseEntity.ok(notebooks);
    }

    /**
     * 更新笔记本接口
     * PUT /api/notebooks/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<Notebook> updateNotebook(@PathVariable Long id, @RequestBody NotebookRequest request) {
        Notebook notebook = notebookService.updateNotebook(id, request);
        return ResponseEntity.ok(notebook);
    }

    /**
     * 删除笔记本接口
     * DELETE /api/notebooks/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotebook(@PathVariable Long id) {
        notebookService.deleteNotebook(id);
        return ResponseEntity.ok().build();
    }
}
