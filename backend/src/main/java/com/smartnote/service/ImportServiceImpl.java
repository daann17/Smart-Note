package com.smartnote.service;

import com.smartnote.entity.*;
import com.smartnote.repository.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ImportServiceImpl implements ImportService {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int MAX_ZIP_FILES = 1000;

    @Autowired
    private ImportTaskRepository importTaskRepository;

    @Autowired
    private ImportTaskLogRepository importTaskLogRepository;

    @Autowired
    private NotebookRepository notebookRepository;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteFolderRepository noteFolderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileService fileService;

    @Override
    public ImportTask importFile(MultipartFile file, Long notebookId, Long userId) {
        // 创建导入任务
        ImportTask task = createImportTask(file, notebookId, userId);
        task.setStatus(ImportTask.ImportStatus.PENDING);
        task = importTaskRepository.save(task);

        // 异步处理导入
        processImportAsync(task, file);

        return task;
    }

    @Async
    private void processImportAsync(ImportTask task, MultipartFile file) {
        try {
            task.setStatus(ImportTask.ImportStatus.PROCESSING);
            task.setCreatedAt(LocalDateTime.now());
            importTaskRepository.save(task);

            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                throw new RuntimeException("文件名为空");
            }

            if (fileName.endsWith(".md")) {
                processMarkdownFile(task, file);
            } else if (fileName.endsWith(".zip")) {
                processZipFile(task, file);
            } else if (fileName.endsWith(".docx")) {
                processWordFile(task, file);
            } else {
                throw new RuntimeException("不支持的文件类型");
            }

            task.setCompletedAt(LocalDateTime.now());
            if (task.getFailureCount() > 0) {
                if (task.getSuccessCount() > 0) {
                    task.setStatus(ImportTask.ImportStatus.PARTIAL_SUCCESS);
                } else {
                    task.setStatus(ImportTask.ImportStatus.FAILED);
                }
            } else {
                task.setStatus(ImportTask.ImportStatus.SUCCESS);
            }
        } catch (Exception e) {
            task.setStatus(ImportTask.ImportStatus.FAILED);
            task.setCompletedAt(LocalDateTime.now());
            ImportTaskLog log = new ImportTaskLog();
            log.setTask(task);
            log.setFilePath(file.getOriginalFilename());
            log.setFileName(file.getOriginalFilename());
            log.setSuccess(false);
            log.setErrorMessage(e.getMessage());
            importTaskLogRepository.save(log);
            task.setFailureCount(1);
        } finally {
            importTaskRepository.save(task);
        }
    }

    private ImportTask createImportTask(MultipartFile file, Long notebookId, Long userId) {
        ImportTask task = new ImportTask();
        task.setUser(userRepository.findById(userId).orElseThrow(() -> new RuntimeException("用户不存在")));
        task.setNotebook(notebookRepository.findById(notebookId).orElseThrow(() -> new RuntimeException("知识库不存在")));
        task.setFileName(file.getOriginalFilename());
        task.setFileSize(file.getSize());
        task.setTotalFiles(0);
        task.setSuccessCount(0);
        task.setFailureCount(0);
        return task;
    }

    private void processMarkdownFile(ImportTask task, MultipartFile file) throws IOException {
        task.setTotalFiles(1);
        importTaskRepository.save(task);

        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            String fileName = file.getOriginalFilename();
            String title = extractTitle(fileName);

            // 处理图片资源
            content = processImages(content, task);

            // 创建笔记
            Note note = createNote(title, content, task.getNotebook(), task.getUser());

            // 记录日志
            ImportTaskLog log = new ImportTaskLog();
            log.setTask(task);
            log.setFilePath(fileName);
            log.setFileName(fileName);
            log.setSuccess(true);
            log.setImportedNoteId(note.getId());
            importTaskLogRepository.save(log);

            task.setSuccessCount(1);
        } catch (Exception e) {
            task.setFailureCount(1);
            ImportTaskLog log = new ImportTaskLog();
            log.setTask(task);
            log.setFilePath(file.getOriginalFilename());
            log.setFileName(file.getOriginalFilename());
            log.setSuccess(false);
            log.setErrorMessage(e.getMessage());
            importTaskLogRepository.save(log);
        }
    }

    private void processZipFile(ImportTask task, MultipartFile file) throws IOException {
        List<String> files = new ArrayList<>();
        try (ZipInputStream zipIn = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".md")) {
                    files.add(entry.getName());
                }
            }
        }

        if (files.size() > MAX_ZIP_FILES) {
            throw new RuntimeException("ZIP包文件数量超过限制（最大1000个）");
        }

        task.setTotalFiles(files.size());
        importTaskRepository.save(task);

        try (ZipInputStream zipIn = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".md")) {
                    try {
                        byte[] buffer = new byte[1024];
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        int len;
                        while ((len = zipIn.read(buffer)) > 0) {
                            baos.write(buffer, 0, len);
                        }
                        String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                        String entryName = entry.getName();

                        // 处理图片资源
                        content = processImages(content, task);

                        // 创建文件夹结构
                        String[] pathParts = entryName.split("/");
                        String title = extractTitle(pathParts[pathParts.length - 1]);
                        NoteFolder folder = createFolderStructure(pathParts, task.getNotebook(), task.getUser());

                        // 创建笔记
                        Note note = createNote(title, content, task.getNotebook(), task.getUser());
                        if (folder != null) {
                            note.setFolder(folder);
                        }
                        noteRepository.save(note);

                        // 记录日志
                        ImportTaskLog log = new ImportTaskLog();
                        log.setTask(task);
                        log.setFilePath(entryName);
                        log.setFileName(pathParts[pathParts.length - 1]);
                        log.setSuccess(true);
                        log.setImportedNoteId(note.getId());
                        importTaskLogRepository.save(log);

                        task.setSuccessCount(task.getSuccessCount() + 1);
                    } catch (Exception e) {
                        task.setFailureCount(task.getFailureCount() + 1);
                        ImportTaskLog log = new ImportTaskLog();
                        log.setTask(task);
                        log.setFilePath(entry.getName());
                        log.setFileName(entry.getName());
                        log.setSuccess(false);
                        log.setErrorMessage(e.getMessage());
                        importTaskLogRepository.save(log);
                    }
                }
            }
        }
    }

    private void processWordFile(ImportTask task, MultipartFile file) throws IOException {
        task.setTotalFiles(1);
        importTaskRepository.save(task);

        try {
            XWPFDocument document = new XWPFDocument(file.getInputStream());
            StringBuilder content = new StringBuilder();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                StringBuilder paraContent = new StringBuilder();
                for (XWPFRun run : paragraph.getRuns()) {
                    paraContent.append(run.getText(0));
                }
                content.append(paraContent).append("\n\n");
            }

            String fileName = file.getOriginalFilename();
            String title = extractTitle(fileName);

            // 创建笔记
            Note note = createNote(title, content.toString(), task.getNotebook(), task.getUser());

            // 记录日志
            ImportTaskLog log = new ImportTaskLog();
            log.setTask(task);
            log.setFilePath(fileName);
            log.setFileName(fileName);
            log.setSuccess(true);
            log.setImportedNoteId(note.getId());
            importTaskLogRepository.save(log);

            task.setSuccessCount(1);
        } catch (Exception e) {
            task.setFailureCount(1);
            ImportTaskLog log = new ImportTaskLog();
            log.setTask(task);
            log.setFilePath(file.getOriginalFilename());
            log.setFileName(file.getOriginalFilename());
            log.setSuccess(false);
            log.setErrorMessage(e.getMessage());
            importTaskLogRepository.save(log);
        }
    }

    private String processImages(String content, ImportTask task) {
        // 这里需要实现图片资源的处理逻辑
        // 暂时返回原内容，后续需要实现图片提取和上传
        return content;
    }

    private String extractTitle(String fileName) {
        if (fileName == null) {
            return "未命名文档-" + System.currentTimeMillis();
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }

    private Note createNote(String title, String content, Notebook notebook, User user) {
        // 处理重名情况
        String finalTitle = title;
        int suffix = 1;
        while (noteRepository.existsByTitleAndNotebookAndUser(finalTitle, notebook, user)) {
            finalTitle = title + "-" + suffix++;
        }

        Note note = new Note();
        note.setTitle(finalTitle);
        note.setContent(content);
        note.setNotebook(notebook);
        note.setUser(user);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(note);
    }

    private NoteFolder createFolderStructure(String[] pathParts, Notebook notebook, User user) {
        if (pathParts.length <= 1) {
            return null;
        }

        NoteFolder parentFolder = null;
        for (int i = 0; i < pathParts.length - 1; i++) {
            String folderName = pathParts[i];
            NoteFolder folder = noteFolderRepository.findByParentFolderAndNameAndNotebook(parentFolder, folderName, notebook);
            if (folder == null) {
                folder = new NoteFolder();
                folder.setName(folderName);
                folder.setParentFolder(parentFolder);
                folder.setNotebook(notebook);
                folder.setUser(user);
                folder = noteFolderRepository.save(folder);
            }
            parentFolder = folder;
        }
        return parentFolder;
    }

    @Override
    public ImportTask getImportTask(Long taskId) {
        return importTaskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("导入任务不存在"));
    }

    @Override
    public List<ImportTask> getUserImportTasks(Long userId) {
        return importTaskRepository.findByUserId(userId);
    }
}