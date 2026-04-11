package com.smartnote.repository;

import com.smartnote.entity.NoteFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 笔记文件夹数据访问层
 */
@Repository
public interface NoteFolderRepository extends JpaRepository<NoteFolder, Long> {

    /**
     * 获取指定笔记本下的所有文件夹，按 sortOrder 升序排列
     */
    List<NoteFolder> findByNotebookIdOrderBySortOrder(Long notebookId);

    /**
     * 获取指定笔记本下的顶层文件夹（无父文件夹）
     */
    List<NoteFolder> findByNotebookIdAndParentFolderIsNullOrderBySortOrder(Long notebookId);

    /**
     * 获取指定父文件夹下的子文件夹（删除时递归使用）
     */
    List<NoteFolder> findByParentFolderId(Long parentFolderId);

    /**
     * 统计指定文件夹的最大 sortOrder，用于新建时追加到末尾
     */
    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM NoteFolder f WHERE f.notebook.id = :notebookId")
    int findMaxSortOrderByNotebookId(@Param("notebookId") Long notebookId);

    NoteFolder findByParentFolderAndNameAndNotebook(NoteFolder parentFolder, String name, com.smartnote.entity.Notebook notebook);
}
