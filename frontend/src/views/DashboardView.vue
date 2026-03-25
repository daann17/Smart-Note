<script setup lang="ts">
import { ref, onMounted } from 'vue';
import api from '../api';
import { FileTextOutlined, BookOutlined, TagOutlined, TeamOutlined } from '@ant-design/icons-vue';

const stats = ref<any>({});
const loading = ref(true);

onMounted(async () => {
  try {
    const response = await api.get('/stats/overview');
    stats.value = response.data;
  } catch (error) {
    console.error('Failed to fetch stats', error);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="dashboard-container">
    <div class="stats-grid" v-if="!loading">
      <a-card hoverable class="stat-card">
        <a-statistic title="我的笔记" :value="stats.totalNotes" :value-style="{ color: '#1890ff' }">
          <template #prefix><FileTextOutlined /></template>
        </a-statistic>
      </a-card>
      
      <a-card hoverable class="stat-card">
        <a-statistic title="我的笔记本" :value="stats.totalNotebooks" :value-style="{ color: '#52c41a' }">
          <template #prefix><BookOutlined /></template>
        </a-statistic>
      </a-card>
      
      <a-card hoverable class="stat-card">
        <a-statistic title="我的标签" :value="stats.totalTags" :value-style="{ color: '#722ed1' }">
          <template #prefix><TagOutlined /></template>
        </a-statistic>
      </a-card>
    </div>
    <div v-else class="loading-state">
      <a-spin size="large" />
    </div>

    <div v-if="!loading && stats.sysTotalUsers !== undefined" class="admin-section">
      <h3 class="section-title">系统管理员概览</h3>
      <div class="stats-grid">
        <a-card hoverable class="stat-card admin-card">
          <a-statistic title="系统总用户" :value="stats.sysTotalUsers" :value-style="{ color: '#cf1322' }">
            <template #prefix><TeamOutlined /></template>
          </a-statistic>
        </a-card>
        <a-card hoverable class="stat-card admin-card">
          <a-statistic title="系统总笔记" :value="stats.sysTotalNotes" :value-style="{ color: '#d4380d' }">
            <template #prefix><FileTextOutlined /></template>
          </a-statistic>
        </a-card>
        <a-card hoverable class="stat-card admin-card">
          <a-statistic title="系统总笔记本" :value="stats.sysTotalNotebooks" :value-style="{ color: '#d48806' }">
            <template #prefix><BookOutlined /></template>
          </a-statistic>
        </a-card>
        <a-card hoverable class="stat-card admin-card">
          <a-statistic title="系统总标签" :value="stats.sysTotalTags" :value-style="{ color: '#08979c' }">
            <template #prefix><TagOutlined /></template>
          </a-statistic>
        </a-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard-container {
  max-width: 1200px;
  margin: 0 auto;
}

.page-title {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 24px;
  color: #1f1f1f;
}

.section-title {
  font-size: 18px;
  font-weight: 500;
  margin: 32px 0 16px;
  color: #434343;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 24px;
}

.stat-card {
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.05);
}

.admin-card {
  background: #fffcf8;
}

.loading-state {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}
</style>