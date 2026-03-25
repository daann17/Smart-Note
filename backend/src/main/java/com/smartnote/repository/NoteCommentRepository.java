package com.smartnote.repository;

import com.smartnote.entity.NoteComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteCommentRepository extends JpaRepository<NoteComment, Long> {
    List<NoteComment> findByShareIdOrderByCreatedAtDesc(Long shareId);
}