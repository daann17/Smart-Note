<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  ArrowLeftOutlined,
  LinkOutlined,
  DeleteOutlined,
  MessageOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons-vue';
import api from '../api';
import { message, Modal } from 'ant-design-vue';

const router = useRouter();
const shares = ref<any[]>([]);
const loading = ref(true);

const shareStats = computed(() => {
  const active = shares.value.filter((item) => item.isActive).length;
  const expired = shares.value.filter((item) => item.expireAt && new Date(item.expireAt) < new Date()).length;

  return {
    total: shares.value.length,
    active,
    expired,
  };
});

const fetchShares = async () => {
  loading.value = true;
  try {
    const response = await api.get('/shares');
    shares.value = response.data;
  } catch (error) {
    message.error('获取分享列表失败');
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  fetchShares();
});

const copyLink = (token: string) => {
  const url = `${window.location.origin}/share/${token}`;
  navigator.clipboard.writeText(url).then(() => {
    message.success('分享链接已复制');
  });
};

const disableShare = (noteId: number) => {
  Modal.confirm({
    title: '关闭分享',
    content: '关闭后当前分享链接会立刻失效，确认继续吗？',
    okText: '确认关闭',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await api.delete(`/shares/note/${noteId}`);
        message.success('分享已关闭');
        fetchShares();
      } catch (error) {
        message.error('操作失败');
      }
    },
  });
};

const deleteShareRecord = (shareId: number) => {
  Modal.confirm({
    title: '删除分享记录',
    content: '删除后会移除该条分享记录及相关评论数据，且无法恢复。',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await api.delete(`/shares/${shareId}`);
        message.success('分享记录已删除');
        fetchShares();
      } catch (error) {
        message.error('删除分享记录失败');
      }
    },
  });
};
</script>

<template>
  <div class="page-shell share-page">
    <header class="page-header">
      <div class="header-main">
        <a-button type="text" class="back-btn" @click="router.push('/home')">
          <template #icon><ArrowLeftOutlined /></template>
          返回工作台
        </a-button>
        <div>
          <span class="page-kicker">Share Center</span>
          <h1 class="page-title">我的分享</h1>
          <p class="page-description">集中查看分享状态、过期时间、提取码与评论入口。</p>
        </div>
      </div>
    </header>

    <section class="share-summary metric-grid">
      <article class="metric-card">
        <span>分享总数</span>
        <strong>{{ shareStats.total }}</strong>
        <small>当前账号累计生成的分享记录</small>
      </article>
      <article class="metric-card">
        <span>生效中</span>
        <strong>{{ shareStats.active }}</strong>
        <small>仍可访问的分享链接</small>
      </article>
      <article class="metric-card">
        <span>已过期</span>
        <strong>{{ shareStats.expired }}</strong>
        <small>需要重新生成或清理的记录</small>
      </article>
    </section>

    <section class="share-table-wrap surface-card">
      <a-table :data-source="shares" :loading="loading" row-key="id" :pagination="false">
        <a-table-column title="笔记标题" data-index="note" key="note">
          <template #default="{ text: note }">
            <a @click="router.push(`/notebook/${note.notebook?.id}?noteId=${note.id}`)">
              {{ note.title || '无标题' }}
            </a>
          </template>
        </a-table-column>

        <a-table-column title="创建时间" data-index="createdAt" key="createdAt">
          <template #default="{ text }">
            {{ new Date(text).toLocaleString() }}
          </template>
        </a-table-column>

        <a-table-column title="过期时间" data-index="expireAt" key="expireAt">
          <template #default="{ text }">
            <span v-if="text">{{ new Date(text).toLocaleString() }}</span>
            <span class="meta-positive" v-else>永久有效</span>
          </template>
        </a-table-column>

        <a-table-column title="提取码" data-index="extractionCode" key="extractionCode">
          <template #default="{ text }">
            <span v-if="text">{{ text }}</span>
            <span class="meta-muted">无</span>
          </template>
        </a-table-column>

        <a-table-column title="状态" data-index="isActive" key="isActive">
          <template #default="{ text, record }">
            <a-tag v-if="!text">已关闭</a-tag>
            <a-tag v-else-if="record.expireAt && new Date(record.expireAt) < new Date()" color="error">已过期</a-tag>
            <a-tag v-else color="success">生效中</a-tag>
          </template>
        </a-table-column>

        <a-table-column title="操作" key="action">
          <template #default="{ record }">
            <div class="action-group">
              <a-button
                type="link"
                size="small"
                @click="copyLink(record.token)"
                :disabled="!record.isActive || (record.expireAt && new Date(record.expireAt) < new Date())"
              >
                <template #icon><LinkOutlined /></template>
                复制链接
              </a-button>
              <a-button
                type="link"
                size="small"
                @click="router.push({ name: 'comments', params: { notebookId: record.note.notebook?.id, noteId: record.note.id } })"
                :disabled="!record.note?.notebook?.id"
              >
                <template #icon><MessageOutlined /></template>
                评论区
              </a-button>
              <a-button type="link" danger size="small" @click="disableShare(record.note.id)" v-if="record.isActive">
                <template #icon><CloseCircleOutlined /></template>
                关闭
              </a-button>
              <a-button type="link" danger size="small" @click="deleteShareRecord(record.id)">
                <template #icon><DeleteOutlined /></template>
                删除记录
              </a-button>
            </div>
          </template>
        </a-table-column>
      </a-table>
    </section>
  </div>
</template>

<style scoped>
.share-page {
  display: grid;
  gap: 20px;
}

.header-main {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.back-btn {
  margin-top: 4px;
}

.share-summary {
  width: min(var(--sn-container-width), 100%);
  margin: 0 auto;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.share-table-wrap {
  width: min(var(--sn-container-width), 100%);
  margin: 0 auto;
  padding: 18px 18px 8px;
}

.action-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.meta-muted {
  color: #a39e98;
}

.meta-positive {
  color: #1aae39;
  font-weight: 600;
}

@media (max-width: 960px) {
  .share-summary {
    grid-template-columns: 1fr;
  }
}
</style>
