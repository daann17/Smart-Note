<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeftOutlined, LinkOutlined, DeleteOutlined } from '@ant-design/icons-vue';
import api from '../api';
import { message, Modal } from 'ant-design-vue';

const router = useRouter();
const shares = ref<any[]>([]);
const loading = ref(true);

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
    message.success('链接已复制');
  });
};

const disableShare = (noteId: number) => {
  Modal.confirm({
    title: '关闭分享',
    content: '关闭后该分享链接将立即失效，确定要关闭吗？',
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
    }
  });
};
</script>

<template>
  <div class="share-manage-layout">
    <div class="header">
      <a-button type="text" shape="circle" @click="router.push('/home')">
        <template #icon><ArrowLeftOutlined /></template>
      </a-button>
      <h2 style="margin: 0 0 0 16px;">我的分享</h2>
    </div>
    
    <div class="content">
      <a-table :dataSource="shares" :loading="loading" rowKey="id" :pagination="false">
        <a-table-column title="笔记标题" dataIndex="note" key="note">
          <template #default="{ text: note }">
            <a @click="router.push(`/notebook/${note.notebook?.id}?noteId=${note.id}`)">{{ note.title || '无标题' }}</a>
          </template>
        </a-table-column>
        <a-table-column title="创建时间" dataIndex="createdAt" key="createdAt">
          <template #default="{ text }">
            {{ new Date(text).toLocaleString() }}
          </template>
        </a-table-column>
        <a-table-column title="过期时间" dataIndex="expireAt" key="expireAt">
          <template #default="{ text }">
            <span v-if="text">{{ new Date(text).toLocaleString() }}</span>
            <span v-else style="color: #52c41a;">永久有效</span>
          </template>
        </a-table-column>
        <a-table-column title="提取码" dataIndex="extractionCode" key="extractionCode">
          <template #default="{ text }">
            <span v-if="text">{{ text }}</span>
            <span v-else style="color: #999;">无</span>
          </template>
        </a-table-column>
        <a-table-column title="状态" dataIndex="isActive" key="isActive">
          <template #default="{ text, record }">
            <a-tag v-if="!text" color="default">已关闭</a-tag>
            <a-tag v-else-if="record.expireAt && new Date(record.expireAt) < new Date()" color="error">已过期</a-tag>
            <a-tag v-else color="success">生效中</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="操作" key="action">
          <template #default="{ record }">
            <div style="display: flex; gap: 8px;">
              <a-button type="link" size="small" @click="copyLink(record.token)" :disabled="!record.isActive || (record.expireAt && new Date(record.expireAt) < new Date())">
                <template #icon><LinkOutlined /></template>
                复制链接
              </a-button>
              <a-button type="link" danger size="small" @click="disableShare(record.note.id)" v-if="record.isActive">
                <template #icon><DeleteOutlined /></template>
                关闭
              </a-button>
            </div>
          </template>
        </a-table-column>
      </a-table>
    </div>
  </div>
</template>

<style scoped>
.share-manage-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #f0f2f5;
}

.header {
  background: #fff;
  padding: 16px 24px;
  display: flex;
  align-items: center;
  box-shadow: 0 1px 4px rgba(0,21,41,.08);
  z-index: 1;
}

.content {
  flex: 1;
  padding: 24px;
  overflow: auto;
}

:deep(.ant-table-wrapper) {
  background: #fff;
  padding: 24px;
  border-radius: 8px;
  box-shadow: 0 1px 2px -2px rgba(0,0,0,0.16), 0 3px 6px 0 rgba(0,0,0,0.12), 0 5px 12px 4px rgba(0,0,0,0.09);
}
</style>