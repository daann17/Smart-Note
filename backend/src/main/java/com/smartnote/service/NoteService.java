package com.smartnote.service;

import com.smartnote.dto.NoteRequest;
import com.smartnote.entity.Note;
import com.smartnote.entity.Notebook;
import com.smartnote.entity.Tag;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.TagRepository;
import com.smartnote.repository.UserRepository;
import com.smartnote.entity.NoteHistory;
import com.smartnote.repository.NoteHistoryRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 笔记业务逻辑层
 */
@Service
public class NoteService {

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NotebookRepository notebookRepository;

    @Autowired
    private TagRepository tagRepository;
    
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NoteHistoryRepository noteHistoryRepository;

    /**
     * 全局搜索
     */
    public List<Note> searchNotes(String username, String keyword) {
        return searchNotes(username, keyword, null, null, null, null);
    }

    public List<Note> searchNotes(
            String username,
            String keyword,
            Long notebookId,
            String tagName,
            LocalDate startDate,
            LocalDate endDate
    ) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date cannot be later than end date");
        }

        LocalDateTime updatedFrom = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime updatedTo = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        String normalizedTagName = tagName == null || tagName.trim().isEmpty() ? null : tagName.trim();

        Specification<Note> specification = (root, query, criteriaBuilder) -> {
            query.distinct(true);

            Join<Note, Notebook> notebookJoin = root.join("notebook");
            Join<Note, Tag> tagJoin = root.joinSet("tags", JoinType.LEFT);
            List<Predicate> predicates = new ArrayList<>();
            String keywordPattern = "%" + normalizedKeyword + "%";

            predicates.add(criteriaBuilder.equal(notebookJoin.get("user").get("id"), user.getId()));
            predicates.add(criteriaBuilder.notEqual(root.get("status"), "TRASH"));
            predicates.add(criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), keywordPattern),
                    criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("content"), "")), keywordPattern),
                    criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(tagJoin.get("name"), "")), keywordPattern)
            ));

            if (notebookId != null) {
                predicates.add(criteriaBuilder.equal(notebookJoin.get("id"), notebookId));
            }

            if (normalizedTagName != null) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(tagJoin.get("name")),
                        normalizedTagName.toLowerCase(Locale.ROOT)
                ));
            }

            if (updatedFrom != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("updatedAt"), updatedFrom));
            }

            if (updatedTo != null) {
                predicates.add(criteriaBuilder.lessThan(root.get("updatedAt"), updatedTo));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };

        List<Note> notes = noteRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "updatedAt"));
        notes.sort(
                Comparator.comparing((Note note) -> !containsIgnoreCase(note.getTitle(), normalizedKeyword))
                        .thenComparing(Note::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
        );
        return notes;
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        if (value == null || normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return false;
        }

        return value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    /**
     * 创建笔记
     */
    @Transactional
    public Note createNote(NoteRequest request) {
        Notebook notebook = notebookRepository.findById(request.getNotebookId())
                .orElseThrow(() -> new RuntimeException("Notebook not found"));

        Note note = new Note();
        note.setNotebook(notebook);
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        note.setContentHtml(request.getContentHtml());
        note.setStatus(request.getStatus() != null ? request.getStatus() : "DRAFT");
        
        if (request.getTags() != null) {
            note.setTags(processTags(request.getTags(), notebook.getUser()));
        }

        Note savedNote = noteRepository.save(note);
        saveHistoryIfNeeded(savedNote, true); // 第一次创建时保存历史
        return savedNote;
    }

    /**
     * 更新笔记
     */
    @Transactional
    public Note updateNote(Long id, NoteRequest request) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Note not found"));

        if (request.getTitle() != null) note.setTitle(request.getTitle());
        if (request.getContent() != null) note.setContent(request.getContent());
        if (request.getContentHtml() != null) note.setContentHtml(request.getContentHtml());
        if (request.getStatus() != null) note.setStatus(request.getStatus());
        
        if (request.getTags() != null) {
            note.setTags(processTags(request.getTags(), note.getNotebook().getUser()));
        }

        Note savedNote = noteRepository.save(note);
        saveHistoryIfNeeded(savedNote, Boolean.TRUE.equals(request.getForceHistory()));
        return savedNote;
    }

    /**
     * 保存历史版本逻辑
     */
    private void saveHistoryIfNeeded(Note note, boolean forceHistory) {
        NoteHistory lastHistory = noteHistoryRepository.findFirstByNoteIdOrderBySavedAtDesc(note.getId());
        boolean shouldSave = false;

        if (lastHistory == null) {
            shouldSave = true;
        } else {
            // 注意：如果是强制保存(如手动点击保存或Ctrl+S)，即使内容和标题没有变化也保存一个版本
            // 因为用户可能只改了标签，或者就是想在此刻打个快照
            if (forceHistory) {
                shouldSave = true;
            } else {
                // 如果是自动保存，且内容和标题都没有变化，则不保存
                if (note.getTitle().equals(lastHistory.getTitle()) && note.getContent().equals(lastHistory.getContent())) {
                    return;
                }

                // 如果没有强制保存，判断距离上次保存是否超过 5 分钟
                long minutesSinceLastSave = Duration.between(lastHistory.getSavedAt(), LocalDateTime.now()).toMinutes();
                if (minutesSinceLastSave >= 5) {
                    shouldSave = true;
                }
            }
        }

        if (shouldSave) {
            NoteHistory history = new NoteHistory();
            history.setNote(note);
            history.setTitle(note.getTitle());
            history.setContent(note.getContent());
            // 如果 contentHtml 为空，尝试直接存入原始 content 避免前端空展示
            history.setContentHtml(note.getContentHtml() != null && !note.getContentHtml().trim().isEmpty() 
                    ? note.getContentHtml() : note.getContent());
            noteHistoryRepository.save(history);
        }
    }

    private Set<Tag> processTags(List<String> tagNames, User user) {
        Set<Tag> tags = new HashSet<>();
        for (String tagName : tagNames) {
            Tag tag = tagRepository.findByUserIdAndName(user.getId(), tagName)
                    .orElseGet(() -> {
                        Tag newTag = new Tag();
                        newTag.setUser(user);
                        newTag.setName(tagName);
                        return tagRepository.save(newTag);
                    });
            tags.add(tag);
        }
        return tags;
    }

    /**
     * 获取笔记本下的笔记列表
     */
    public List<Note> getNotesByNotebook(Long notebookId) {
        return noteRepository.findByNotebookIdAndStatusNotOrderByUpdatedAtDesc(notebookId, "TRASH");
    }

    /**
     * 获取单篇笔记详情
     */
    public Note getNoteById(Long id) {
        return noteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Note not found"));
    }

    /**
     * 获取笔记的历史版本列表
     */
    public List<NoteHistory> getNoteHistories(Long noteId) {
        return noteHistoryRepository.findByNoteIdOrderBySavedAtDesc(noteId);
    }

    /**
     * 移动笔记到另一个笔记本
     */
    @Transactional
    public Note moveNote(Long noteId, Long targetNotebookId) {
        Note note = getNoteById(noteId);
        if (note.getNotebook().getId().equals(targetNotebookId)) {
            return note; // Already in target notebook
        }
        Notebook targetNotebook = notebookRepository.findById(targetNotebookId)
                .orElseThrow(() -> new RuntimeException("Target notebook not found"));
        
        note.setNotebook(targetNotebook);
        return noteRepository.save(note);
    }

    /**
     * 复制笔记到目标笔记本
     */
    @Transactional
    public Note copyNote(Long noteId, Long targetNotebookId) {
        Note originalNote = getNoteById(noteId);
        Notebook targetNotebook = notebookRepository.findById(targetNotebookId)
                .orElseThrow(() -> new RuntimeException("Target notebook not found"));
        
        Note newNote = new Note();
        newNote.setNotebook(targetNotebook);
        newNote.setTitle(originalNote.getTitle() + " (副本)");
        newNote.setContent(originalNote.getContent());
        newNote.setContentHtml(originalNote.getContentHtml());
        newNote.setStatus(originalNote.getStatus());
        newNote.setSummary(originalNote.getSummary());
        
        // 复制标签
        if (originalNote.getTags() != null && !originalNote.getTags().isEmpty()) {
            newNote.setTags(new HashSet<>(originalNote.getTags()));
        }
        
        Note savedNote = noteRepository.save(newNote);
        saveHistoryIfNeeded(savedNote, true);
        return savedNote;
    }

    /**
     * 获取单个历史版本详情
     */
    public NoteHistory getNoteHistoryById(Long historyId) {
        return noteHistoryRepository.findById(historyId)
                .orElseThrow(() -> new RuntimeException("History not found"));
    }

    /**
     * 回滚到指定历史版本
     */
    @Transactional
    public Note rollbackToHistory(Long noteId, Long historyId) {
        Note note = getNoteById(noteId);
        NoteHistory history = getNoteHistoryById(historyId);

        if (!history.getNote().getId().equals(noteId)) {
            throw new RuntimeException("History does not belong to this note");
        }

        note.setTitle(history.getTitle());
        note.setContent(history.getContent());
        note.setContentHtml(history.getContentHtml());

        Note savedNote = noteRepository.save(note);
        
        // 回滚后强制生成一次新的历史记录，以记录这次回滚操作带来的状态
        saveHistoryIfNeeded(savedNote, true);

        return savedNote;
    }

    /**
     * 删除笔记 (软删除，移入回收站)
     */
    @Transactional
    public void deleteNote(Long id) {
        Note note = getNoteById(id);
        note.setStatus("TRASH");
        noteRepository.save(note);
    }

    /**
     * 获取用户最近编辑的笔记
     */
    public List<Note> getRecentNotes(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return noteRepository.findTop10ByNotebookUserIdAndStatusNotOrderByUpdatedAtDesc(user.getId(), "TRASH");
    }

    /**
     * 获取回收站中的笔记
     */
    public List<Note> getTrashNotes(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return noteRepository.findByNotebookUserIdAndStatusOrderByUpdatedAtDesc(user.getId(), "TRASH");
    }

    /**
     * 恢复回收站中的笔记
     */
    @Transactional
    public Note restoreNote(Long id) {
        Note note = getNoteById(id);
        if ("TRASH".equals(note.getStatus())) {
            note.setStatus("DRAFT");
            
            // 检查笔记所属的笔记本是否在回收站中，如果在，连同笔记本一起恢复
            Notebook notebook = note.getNotebook();
            if ("TRASH".equals(notebook.getStatus())) {
                notebook.setStatus("NORMAL");
                notebookRepository.save(notebook);
            }
            
            return noteRepository.save(note);
        }
        return note;
    }

    /**
     * 清空回收站
     */
    @Transactional
    public void emptyTrash(String username) {
        List<Note> trashNotes = getTrashNotes(username);
        for (Note note : trashNotes) {
            hardDeleteNote(note.getId());
        }
    }

    /**
     * 彻底删除笔记
     */
    @Transactional
    public void hardDeleteNote(Long id) {
        Note note = getNoteById(id);
        
        // 删除关联的标签关系
        note.getTags().clear();
        noteRepository.save(note);
        
        // 先删除关联的历史版本
        noteHistoryRepository.deleteByNoteId(id);
        
        // 最后删除笔记
        noteRepository.delete(note);
    }

    /**
     * 定时任务：清理超过 14 天的回收站笔记
     * 每天凌晨 2 点执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupExpiredTrashNotes() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(14);
        List<Note> expiredNotes = noteRepository.findByStatusAndUpdatedAtBefore("TRASH", cutoffDate);
        for (Note note : expiredNotes) {
            hardDeleteNote(note.getId());
        }
    }
}
