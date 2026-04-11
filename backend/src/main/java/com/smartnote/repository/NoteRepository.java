package com.smartnote.repository;

import com.smartnote.entity.Note;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NoteRepository extends JpaRepository<Note, Long>, JpaSpecificationExecutor<Note> {

    List<Note> findByNotebookIdAndStatusNotOrderByUpdatedAtDesc(Long notebookId, String status);

    @Query(value = """
        SELECT n.* FROM notes n
        JOIN notebooks nb ON n.notebook_id = nb.id
        WHERE nb.user_id = :userId
        AND n.status <> 'TRASH'
        AND (
            LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(n.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR EXISTS (
                SELECT 1
                FROM note_tags nt
                JOIN tags t ON nt.tag_id = t.id
                WHERE nt.note_id = n.id
                AND LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
        )
        ORDER BY
            CASE WHEN LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) THEN 1 ELSE 2 END,
            n.updated_at DESC
        """, nativeQuery = true)
    List<Note> searchNotes(@Param("userId") Long userId, @Param("keyword") String keyword);

    List<Note> findTop10ByNotebookUserIdAndStatusNotOrderByUpdatedAtDesc(Long userId, String status);

    List<Note> findByNotebookUserIdAndStatusNotOrderByUpdatedAtDesc(Long userId, String status);

    long countByNotebookUserIdAndStatusNot(Long userId, String status);

    long countByStatusNot(String status);

    boolean existsByIdAndNotebookUserUsername(Long id, String username);

    List<Note> findByNotebookUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status);

    List<Note> findByNotebookId(Long notebookId);

    List<Note> findByStatusAndUpdatedAtBefore(String status, LocalDateTime cutoffDate);

    /** 获取指定文件夹内的所有笔记（用于删除文件夹时孤儿化处理） */
    List<Note> findByFolderId(Long folderId);

    @Query("""
        SELECT n.notebook.user.id AS userId, COUNT(n) AS total
        FROM Note n
        WHERE n.status <> :status
        GROUP BY n.notebook.user.id
        """)
    List<UserOwnedCountProjection> countByUserIdGroupedExcludingStatus(@Param("status") String status);

    @Query("""
        SELECT n.notebook.user.id AS userId,
               COALESCE(SUM(
                   LENGTH(COALESCE(n.title, ''))
                   + LENGTH(COALESCE(n.content, ''))
                   + LENGTH(COALESCE(n.contentHtml, ''))
                   + LENGTH(COALESCE(n.summary, ''))
               ), 0) AS total
        FROM Note n
        WHERE n.status <> :status
        GROUP BY n.notebook.user.id
        """)
    List<UserOwnedCountProjection> sumEstimatedStorageByUserIdGroupedExcludingStatus(@Param("status") String status);
}
