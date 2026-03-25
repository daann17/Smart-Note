import { defineStore } from 'pinia';
import { ref } from 'vue';
import api from '../api';

export interface Tag {
  id: number;
  name: string;
}

export const useTagStore = defineStore('tag', () => {
  const tags = ref<Tag[]>([]);
  const loading = ref(false);

  const fetchTags = async () => {
    loading.value = true;
    try {
      const response = await api.get('/tags');
      tags.value = response.data;
    } finally {
      loading.value = false;
    }
  };

  return {
    tags,
    loading,
    fetchTags,
  };
});
