package com.smartnote.repository;

import com.smartnote.entity.Note;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 笔记数据访问层
 */
@Repository
public interface NoteRepository extends JpaRepository<Note, Long>, JpaSpecificationExecutor<Note> {

    /**
     * 查询指定笔记本下的所有笔记 (不包含回收站)
     * 按更新时间倒序排列
     */
    List<Note> findByNotebookIdAndStatusNotOrderByUpdatedAtDesc(Long notebookId, String status);

    /**
     * 全局全文搜索 (不包含回收站)
     * 1. 搜索范围：标题、纯文本内容、标签
     * 2. 权限控制：仅搜索当前用户的笔记
     * 3. 排序：优先匹配标题，其次匹配内容
     */
    @Query(value = """
        SELECT DISTINCT n.* FROM notes n
        LEFT JOIN notebooks nb ON n.notebook_id = nb.id
        LEFT JOIN note_tags nt ON n.id = nt.note_id
        LEFT JOIN tags t ON nt.tag_id = t.id
        WHERE nb.user_id = :userId
        AND n.status != 'TRASH'
        AND (
            LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(n.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
        )
        ORDER BY 
            CASE WHEN LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) THEN 1 ELSE 2 END,
            n.updated_at DESC
        """, nativeQuery = true)
    List<Note> searchNotes(@Param("userId") Long userId, @Param("keyword") String keyword);

    /**
     * 获取用户最近更新的笔记 (不包含回收站)
     */
    List<Note> findTop10ByNotebookUserIdAndStatusNotOrderByUpdatedAtDesc(Long userId, String status);

    List<Note> findByNotebookUserIdAndStatusNotOrderByUpdatedAtDesc(Long userId, String status);

    long countByNotebookUserIdAndStatusNot(Long userId, String status);

    long countByStatusNot(String status);

    boolean existsByIdAndNotebookUserUsername(Long id, String username);

    /**
     * 获取用户的回收站笔记
     */
    List<Note> findByNotebookUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status);

    /**
     * 查询指定笔记本下的所有笔记（包含回收站）
     */
    List<Note> findByNotebookId(Long notebookId);

    /**
     * 查询指定时间之前移入回收站的笔记
     */
    List<Note> findByStatusAndUpdatedAtBefore(String status, java.time.LocalDateTime cutoffDate);

    @Query("""
        SELECT n.notebook.user.id AS userId, COUNT(n) AS total
        FROM Note n
        WHERE n.status <> :status
        GROUP BY n.notebook.user.id
        """)
    List<UserOwnedCountProjection> countByUserIdGroupedExcludingStatus(@Param("status") String status);
}
