package com.smartnote.repository;

import com.smartnote.entity.Tag;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    List<Tag> findByUserId(Long userId);
    
    List<Tag> findByUserIdOrderByNameAsc(Long userId);

    Optional<Tag> findByUserIdAndName(Long userId, String name);

    long countByUserId(Long userId);

    @Query("""
        SELECT t.user.id AS userId, COUNT(t) AS total
        FROM Tag t
        GROUP BY t.user.id
        """)
    List<UserOwnedCountProjection> countByUserIdGrouped();
}
