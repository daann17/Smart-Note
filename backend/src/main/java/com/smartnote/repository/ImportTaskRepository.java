package com.smartnote.repository;

import com.smartnote.entity.ImportTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportTaskRepository extends JpaRepository<ImportTask, Long> {
    List<ImportTask> findByUserId(Long userId);
}