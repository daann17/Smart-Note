import { defineStore } from 'pinia';
import { ref } from 'vue';
import api from '../api';

// ──────────────────────────────────────────────────────────────────────────────
// 类型定义
// ──────────────────────────────────────────────────────────────────────────────
export interface NoteFolder {
  id: number;
  name: string;
  sortOrder: number;
  /** 父文件夹 ID，null 表示顶层文件夹 */
  parentFolder: { id: number; name: string } | null;
  createdAt: string;
  updatedAt: string;
}

export const useFolderStore = defineStore('folder', () => {
  const folders = ref<NoteFolder[]>([]);
  const loading = ref(false);

  // ────────────────────────────────────────────────────────────────────────────
  // 获取笔记本下的所有文件夹（扁平列表）
  // ────────────────────────────────────────────────────────────────────────────
  const fetchFolders = async (notebookId: number) => {
    loading.value = true;
    try {
      const res = await api.get(`/notebooks/${notebookId}/folders`);
      folders.value = res.data;
    } finally {
      loading.value = false;
    }
  };

  /** 新建文件夹 */
  const createFolder = async (notebookId: number, name: string, parentFolderId?: number) => {
    const res = await api.post(`/notebooks/${notebookId}/folders`, {
      name,
      parentFolderId: parentFolderId ?? null,
    });
    const newFolder: NoteFolder = res.data;
    folders.value.push(newFolder);
    return newFolder;
  };

  /** 重命名文件夹 */
  const renameFolder = async (folderId: number, newName: string) => {
    const res = await api.put(`/folders/${folderId}`, { name: newName });
    const updated: NoteFolder = res.data;
    const idx = folders.value.findIndex((f) => f.id === folderId);
    if (idx !== -1) folders.value[idx] = updated;
    return updated;
  };

  /** 删除文件夹（服务端将其中的笔记移至根目录） */
  const deleteFolder = async (folderId: number) => {
    await api.delete(`/folders/${folderId}`);
    folders.value = folders.value.filter((f) => f.id !== folderId);
  };

  /** 将笔记移入文件夹（folderId 为 null 表示移至根目录） */
  const moveNoteToFolder = async (noteId: number, folderId: number | null) => {
    const params = folderId != null ? { folderId } : {};
    await api.post(`/notes/${noteId}/move-to-folder`, null, { params });
  };

  /** 清空当前缓存（切换笔记本时调用） */
  const clearFolders = () => {
    folders.value = [];
  };

  return {
    folders,
    loading,
    fetchFolders,
    createFolder,
    renameFolder,
    deleteFolder,
    moveNoteToFolder,
    clearFolders,
  };
});
