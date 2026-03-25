package com.smartnote.repository;

import com.smartnote.entity.NoteShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NoteShareRepository extends JpaRepository<NoteShare, Long> {
    Optional<NoteShare> findByNoteId(Long noteId);
    Optional<NoteShare> findByToken(String token);

    @Query("""
            SELECT COUNT(s) > 0
            FROM NoteShare s
            WHERE s.token = :token
              AND s.note.id = :noteId
              AND s.isActive = true
              AND s.allowEdit = true
              AND (s.expireAt IS NULL OR s.expireAt > :now)
            """)
    boolean existsActiveEditableShareForNote(
            @Param("token") String token,
            @Param("noteId") Long noteId,
            @Param("now") LocalDateTime now
    );

    @Query("SELECT s FROM NoteShare s JOIN s.note n JOIN n.notebook nb WHERE nb.user.id = :userId ORDER BY s.createdAt DESC")
    List<NoteShare> findByUserId(@Param("userId") Long userId);
}
