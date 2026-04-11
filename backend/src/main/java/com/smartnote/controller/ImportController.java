package com.smartnote.controller;

import com.smartnote.entity.ImportTask;
import com.smartnote.service.ImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    @Autowired
    private ImportService importService;

    /**
     * 导入文件到指定知识库
     * @param file 上传的文件
     * @param notebookId 目标知识库ID
     * @param authentication 认证信息
     * @return 导入任务
     */
    @PostMapping
    public ResponseEntity<ImportTask> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("notebookId") Long notebookId,
            Authentication authentication) {
        try {
            // 检查文件大小限制
            long maxFileSize = 100 * 1024 * 1024; // 100MB
            if (file.getSize() > maxFileSize) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
            }

            Long userId = Long.parseLong(authentication.getName());
            ImportTask task = importService.importFile(file, notebookId, userId);
            return ResponseEntity.ok(task);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
        }
    }

    /**
     * 获取导入任务状态
     * @param taskId 任务ID
     * @param authentication 认证信息
     * @return 导入任务
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<ImportTask> getImportTask(
            @PathVariable Long taskId,
            Authentication authentication) {
        try {
            Long userId = Long.parseLong(authentication.getName());
            ImportTask task = importService.getImportTask(taskId);
            if (!task.getUser().getId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }
            return ResponseEntity.ok(task);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    /**
     * 获取用户的导入任务列表
     * @param authentication 认证信息
     * @return 导入任务列表
     */
    @GetMapping("/tasks")
    public ResponseEntity<List<ImportTask>> getUserImportTasks(Authentication authentication) {
        try {
            Long userId = Long.parseLong(authentication.getName());
            List<ImportTask> tasks = importService.getUserImportTasks(userId);
            return ResponseEntity.ok(tasks);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}