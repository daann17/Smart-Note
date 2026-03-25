import { defineStore } from 'pinia';
import { ref } from 'vue';
import api from '../api';

export interface Notebook {
  id: number;
  name: string;
  description: string;
  isPublic: boolean;
  createdAt: string;
}

export const useNotebookStore = defineStore('notebook', () => {
  const notebooks = ref<Notebook[]>([]);
  const loading = ref(false);

  const fetchNotebooks = async () => {
    loading.value = true;
    try {
      const response = await api.get('/notebooks');
      notebooks.value = response.data;
    } catch (error) {
      console.error('Failed to fetch notebooks:', error);
      notebooks.value = [];
    } finally {
      loading.value = false;
    }
  };

  const createNotebook = async (name: string, description: string, isPublic: boolean) => {
    try {
      const response = await api.post('/notebooks', { name, description, isPublic });
      notebooks.value.unshift(response.data); // 添加到列表开头
      return true;
    } catch (error) {
      return false;
    }
  };

  const updateNotebook = async (id: number, data: Partial<Notebook>) => {
    try {
      const response = await api.put(`/notebooks/${id}`, data);
      const index = notebooks.value.findIndex(nb => nb.id === id);
      if (index !== -1) {
        notebooks.value[index] = response.data;
      }
      return true;
    } catch (error) {
      console.error('Failed to update notebook:', error);
      return false;
    }
  };

  const deleteNotebook = async (id: number) => {
    try {
      await api.delete(`/notebooks/${id}`);
      notebooks.value = notebooks.value.filter(nb => nb.id !== id);
      return true;
    } catch (error) {
      console.error('Failed to delete notebook:', error);
      return false;
    }
  };

  return {
    notebooks,
    loading,
    fetchNotebooks,
    createNotebook,
    updateNotebook,
    deleteNotebook,
  };
});
