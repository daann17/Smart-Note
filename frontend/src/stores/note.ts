import { defineStore } from 'pinia';
import { ref } from 'vue';
import api from '../api';
import type { Tag } from './tag';

export interface Note {
  id: number;
  notebookId: number;
  notebook?: {
    id: number;
    name: string;
  };
  /** 所属文件夹 ID，null 表示笔记位于笔记本根目录 */
  folderId?: number | null;
  folder?: { id: number; name: string } | null;
  title: string;
  content: string;
  contentHtml: string;
  summary?: string;
  status: string;
  updatedAt: string;
  tags: Tag[];
}

export interface NoteHistory {
  id: number;
  noteId: number;
  title: string;
  content: string;
  contentHtml: string;
  savedAt: string;
}

export interface ShareComment {
  id: number;
  parentCommentId: number | null;
  content: string;
  authorName: string;
  authorComment: boolean;
  createdAt: string;
  anchorKey: string | null;
  anchorType: string | null;
  anchorLabel: string | null;
  anchorPreview: string | null;
  resolved: boolean;
  resolvedAt: string | null;
  resolvedBy: string | null;
  viewerCanDelete: boolean;
  replies: ShareComment[];
}

export interface ShareCommentPayload {
  content: string;
  parentCommentId?: number;
}

export interface NoteSearchFilters {
  notebookId?: number;
  tagName?: string;
  startDate?: string;
  endDate?: string;
}

export const useNoteStore = defineStore('note', () => {
  const notes = ref<Note[]>([]);
  const trashNotes = ref<Note[]>([]);
  const currentNote = ref<Note | null>(null);
  const noteHistories = ref<NoteHistory[]>([]);
  const loading = ref(false);

  const downloadBlob = (data: BlobPart, mimeType: string, fileName: string) => {
    const blob = new Blob([data], { type: mimeType });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', fileName);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  };

  const fetchNotes = async (notebookId: number) => {
    loading.value = true;
    try {
      const response = await api.get(`/notes/notebook/${notebookId}`);
      notes.value = response.data;
    } finally {
      loading.value = false;
    }
  };

  const createNote = async (notebookId: number) => {
    try {
      const response = await api.post('/notes', {
        notebookId,
        title: '无标题笔记',
        content: '',
        status: 'DRAFT',
      });
      notes.value.unshift(response.data);
      currentNote.value = response.data;
      return response.data;
    } catch (error) {
      return null;
    }
  };

  const updateNote = async (id: number, data: any) => {
    try {
      const response = await api.put(`/notes/${id}`, data);
      const index = notes.value.findIndex((note) => note.id === id);
      if (index !== -1) {
        notes.value[index] = response.data;
      }
      if (currentNote.value?.id === id) {
        currentNote.value = response.data;
      }
    } catch (error) {
      // Keep caller-side behavior unchanged.
    }
  };

  const getNoteDetail = async (id: number) => {
    loading.value = true;
    try {
      const response = await api.get(`/notes/${id}`);
      currentNote.value = response.data;
    } finally {
      loading.value = false;
    }
  };

  const searchNotes = async (keyword: string, filters: NoteSearchFilters = {}) => {
    loading.value = true;
    try {
      const params: Record<string, string | number> = { q: keyword };

      if (filters.notebookId !== undefined) {
        params.notebookId = filters.notebookId;
      }
      if (filters.tagName) {
        params.tag = filters.tagName;
      }
      if (filters.startDate) {
        params.startDate = filters.startDate;
      }
      if (filters.endDate) {
        params.endDate = filters.endDate;
      }

      const response = await api.get('/notes/search', { params });
      return response.data;
    } finally {
      loading.value = false;
    }
  };

  const fetchNoteHistories = async (noteId: number) => {
    try {
      const response = await api.get(`/notes/${noteId}/history`);
      noteHistories.value = response.data;
    } catch (error) {
      console.error('Failed to fetch note histories', error);
    }
  };

  const rollbackToHistory = async (noteId: number, historyId: number) => {
    try {
      const response = await api.post(`/notes/${noteId}/rollback/${historyId}`);
      if (currentNote.value?.id === noteId) {
        currentNote.value = response.data;
      }
      const index = notes.value.findIndex((note) => note.id === noteId);
      if (index !== -1) {
        notes.value[index] = response.data;
      }
      return response.data;
    } catch (error) {
      console.error('Failed to rollback history', error);
      return null;
    }
  };

  const generateSummary = async (noteId: number) => {
    try {
      const response = await api.post(`/notes/${noteId}/summary`);
      if (currentNote.value?.id === noteId) {
        currentNote.value = response.data;
      }
      const index = notes.value.findIndex((note) => note.id === noteId);
      if (index !== -1) {
        notes.value[index] = response.data;
      }
      return response.data;
    } catch (error) {
      console.error('Failed to generate summary', error);
      throw error;
    }
  };

  const suggestTags = async (noteId: number) => {
    try {
      const response = await api.get(`/notes/${noteId}/suggest-tags`);
      return response.data;
    } catch (error) {
      console.error('Failed to suggest tags', error);
      throw error;
    }
  };

  const moveNote = async (noteId: number, targetNotebookId: number) => {
    try {
      const response = await api.post(`/notes/${noteId}/move`, null, {
        params: { targetNotebookId },
      });
      notes.value = notes.value.filter((note) => note.id !== noteId);
      if (currentNote.value?.id === noteId) {
        currentNote.value = null;
      }
      return response.data;
    } catch (error) {
      console.error('Failed to move note', error);
      throw error;
    }
  };

  const copyNote = async (noteId: number, targetNotebookId: number) => {
    try {
      const response = await api.post(`/notes/${noteId}/copy`, null, {
        params: { targetNotebookId },
      });
      return response.data;
    } catch (error) {
      console.error('Failed to copy note', error);
      throw error;
    }
  };

  const deleteNote = async (id: number) => {
    try {
      await api.delete(`/notes/${id}`);
      notes.value = notes.value.filter((note) => note.id !== id);
      if (currentNote.value?.id === id) {
        currentNote.value = null;
      }
    } catch (error) {
      console.error('Failed to delete note', error);
      throw error;
    }
  };

  const fetchTrashNotes = async () => {
    loading.value = true;
    try {
      const response = await api.get('/notes/trash');
      trashNotes.value = response.data;
    } finally {
      loading.value = false;
    }
  };

  const restoreNote = async (id: number) => {
    try {
      const response = await api.post(`/notes/${id}/restore`);
      trashNotes.value = trashNotes.value.filter((note) => note.id !== id);
      return response.data;
    } catch (error) {
      console.error('Failed to restore note', error);
      throw error;
    }
  };

  const emptyTrash = async () => {
    try {
      await api.delete('/notes/trash/empty');
      trashNotes.value = [];
    } catch (error) {
      console.error('Failed to empty trash', error);
      throw error;
    }
  };

  const hardDeleteNote = async (id: number) => {
    try {
      await api.delete(`/notes/${id}/hard`);
      trashNotes.value = trashNotes.value.filter((note) => note.id !== id);
    } catch (error) {
      console.error('Failed to hard delete note', error);
      throw error;
    }
  };

  const fetchRecentNotes = async () => {
    try {
      const response = await api.get('/notes/recent');
      return response.data;
    } catch (error) {
      console.error('Failed to fetch recent notes', error);
      return [];
    }
  };

  const createShare = async (
    noteId: number,
    expireDays?: number,
    extractionCode?: string,
    allowComment?: boolean,
    allowEdit?: boolean,
  ) => {
    try {
      const response = await api.post(`/shares/note/${noteId}`, {
        expireDays,
        extractionCode,
        allowComment,
        allowEdit,
      });
      return response.data;
    } catch (error) {
      console.error('Failed to create share', error);
      throw error;
    }
  };

  const getShare = async (noteId: number) => {
    try {
      const response = await api.get(`/shares/note/${noteId}`);
      return response.data;
    } catch (error) {
      console.error('Failed to get share', error);
      return null;
    }
  };

  const disableShare = async (noteId: number) => {
    try {
      await api.delete(`/shares/note/${noteId}`);
    } catch (error) {
      console.error('Failed to disable share', error);
      throw error;
    }
  };

  const getShareComments = async (noteId: number) => {
    try {
      const response = await api.get<ShareComment[]>(`/shares/note/${noteId}/comments`);
      return response.data;
    } catch (error) {
      console.error('Failed to get share comments', error);
      throw error;
    }
  };

  const replyToShareComment = async (noteId: number, data: ShareCommentPayload) => {
    try {
      const response = await api.post<ShareComment>(`/shares/note/${noteId}/comments`, data);
      return response.data;
    } catch (error) {
      console.error('Failed to reply share comment', error);
      throw error;
    }
  };

  const resolveShareComment = async (noteId: number, commentId: number, resolved: boolean) => {
    try {
      const response = await api.put<ShareComment>(`/shares/note/${noteId}/comments/${commentId}/resolve`, {
        resolved,
      });
      return response.data;
    } catch (error) {
      console.error('Failed to update share comment status', error);
      throw error;
    }
  };

  const deleteShareComment = async (noteId: number, commentId: number) => {
    try {
      await api.delete(`/shares/note/${noteId}/comments/${commentId}`);
    } catch (error) {
      console.error('Failed to delete share comment', error);
      throw error;
    }
  };

  const exportNoteToMarkdown = async (id: number, title: string) => {
    try {
      const response = await api.get(`/notes/${id}/export/markdown`, {
        responseType: 'blob',
      });
      downloadBlob(response.data, 'text/markdown;charset=utf-8', `${title}.md`);
      return true;
    } catch (error) {
      console.error('Failed to export note', error);
      return false;
    }
  };

  const exportNoteToPdf = async (id: number, title: string) => {
    try {
      const response = await api.get(`/notes/${id}/export/pdf`, {
        responseType: 'blob',
      });
      downloadBlob(response.data, 'application/pdf', `${title}.pdf`);
      return true;
    } catch (error) {
      console.error('Failed to export note as PDF', error);
      return false;
    }
  };

  const exportNoteToWord = async (id: number, title: string) => {
    try {
      const response = await api.get(`/notes/${id}/export/word`, {
        responseType: 'blob',
      });
      downloadBlob(
        response.data,
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        `${title}.docx`,
      );
      return true;
    } catch (error) {
      console.error('Failed to export note as Word', error);
      return false;
    }
  };

  return {
    notes,
    trashNotes,
    currentNote,
    noteHistories,
    loading,
    fetchNotes,
    createNote,
    updateNote,
    getNoteDetail,
    searchNotes,
    fetchNoteHistories,
    rollbackToHistory,
    generateSummary,
    suggestTags,
    moveNote,
    copyNote,
    deleteNote,
    fetchTrashNotes,
    restoreNote,
    emptyTrash,
    hardDeleteNote,
    fetchRecentNotes,
    createShare,
    getShare,
    disableShare,
    getShareComments,
    replyToShareComment,
    resolveShareComment,
    deleteShareComment,
    exportNoteToMarkdown,
    exportNoteToPdf,
    exportNoteToWord,
  };
});
