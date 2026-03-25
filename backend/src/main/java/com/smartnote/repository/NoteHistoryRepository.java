package com.smartnote.repository;

import com.smartnote.entity.NoteHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteHistoryRepository extends JpaRepository<NoteHistory, Long> {
    
    /**
     * 查询某篇笔记的所有历史版本，按保存时间倒序排列
     */
    List<NoteHistory> findByNoteIdOrderBySavedAtDesc(Long noteId);
    
    /**
     * 获取某篇笔记最新的一条历史记录
     */
    NoteHistory findFirstByNoteIdOrderBySavedAtDesc(Long noteId);

    /**
     * 删除某篇笔记的所有历史版本
     */
    void deleteByNoteId(Long noteId);
}
