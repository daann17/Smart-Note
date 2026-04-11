package com.smartnote.service;

import com.smartnote.entity.ImportTask;
import org.springframework.web.multipart.MultipartFile;

public interface ImportService {
    /**
     * 导入文件到指定知识库
     * @param file 上传的文件
     * @param notebookId 目标知识库ID
     * @param userId 用户ID
     * @return 导入任务
     */
    ImportTask importFile(MultipartFile file, Long notebookId, Long userId);

    /**
     * 获取导入任务状态
     * @param taskId 任务ID
     * @return 导入任务
     */
    ImportTask getImportTask(Long taskId);

    /**
     * 获取用户的导入任务列表
     * @param userId 用户ID
     * @return 导入任务列表
     */
    java.util.List<ImportTask> getUserImportTasks(Long userId);
}