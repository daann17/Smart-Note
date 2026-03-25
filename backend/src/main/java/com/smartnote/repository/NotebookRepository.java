package com.smartnote.repository;

import com.smartnote.entity.Notebook;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 笔记本数据访问层
 */
@Repository
public interface NotebookRepository extends JpaRepository<Notebook, Long> {
    
    /**
     * 查询指定用户的所有未删除的笔记本，按创建时间倒序排列
     */
    List<Notebook> findByUserIdAndStatusNotOrderByCreatedAtDesc(Long userId, String status);

    long countByUserIdAndStatusNot(Long userId, String status);

    long countByStatusNot(String status);

    @Query("""
        SELECT n.user.id AS userId, COUNT(n) AS total
        FROM Notebook n
        WHERE n.status <> :status
        GROUP BY n.user.id
        """)
    List<UserOwnedCountProjection> countByUserIdGroupedExcludingStatus(@Param("status") String status);
}
