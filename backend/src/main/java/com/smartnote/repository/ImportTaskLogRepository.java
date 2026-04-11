package com.smartnote.repository;

import com.smartnote.entity.ImportTaskLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportTaskLogRepository extends JpaRepository<ImportTaskLog, Long> {
    List<ImportTaskLog> findByTaskId(Long taskId);
}