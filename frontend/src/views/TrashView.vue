<script setup lang="ts">
import { computed, createVNode, onMounted } from 'vue';
import { useNoteStore } from '../stores/note';
import { useRouter } from 'vue-router';
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  ReloadOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons-vue';
import { message, Modal } from 'ant-design-vue';

const noteStore = useNoteStore();
const router = useRouter();

const trashCount = computed(() => noteStore.trashNotes.length);

onMounted(() => {
  noteStore.fetchTrashNotes();
});

const handleRestore = async (id: number) => {
  try {
    await noteStore.restoreNote(id);
    message.success('笔记已恢复');
  } catch (error) {
    message.error('恢复失败');
  }
};

const handleHardDelete = (id: number) => {
  Modal.confirm({
    title: '确认彻底删除',
    icon: createVNode(ExclamationCircleOutlined),
    content: '彻底删除后无法恢复，确认继续吗？',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await noteStore.hardDeleteNote(id);
        message.success('笔记已彻底删除');
      } catch (error) {
        message.error('删除失败');
      }
    },
  });
};

const handleEmptyTrash = () => {
  if (noteStore.trashNotes.length === 0) {
    message.info('回收站已经是空的');
    return;
  }

  Modal.confirm({
    title: '确认清空回收站',
    icon: createVNode(ExclamationCircleOutlined),
    content: '清空后所有回收站笔记都将不可恢复。',
    okText: '确认清空',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await noteStore.emptyTrash();
        message.success('回收站已清空');
      } catch (error) {
        message.error('清空失败');
      }
    },
  });
};
</script>

<template>
  <div class="page-shell trash-page">
    <header class="page-header">
      <div class="header-main">
        <a-button type="text" class="back-btn" @click="router.push('/home')">
          <template #icon><ArrowLeftOutlined /></template>
          返回工作台
        </a-button>
        <div>
          <span class="page-kicker">Trash</span>
          <h1 class="page-title">回收站</h1>
          <p class="page-description">这里保留被移除的笔记，你可以恢复它们，或者执行彻底删除。</p>
        </div>
      </div>

      <a-button danger @click="handleEmptyTrash" :disabled="trashCount === 0">
        <template #icon><DeleteOutlined /></template>
        清空回收站
      </a-button>
    </header>

    <section class="trash-summary metric-grid">
      <article class="metric-card">
        <span>待处理笔记</span>
        <strong>{{ trashCount }}</strong>
        <small>保留在回收站中的内容数量</small>
      </article>
    </section>

    <section class="trash-panel surface-card">
      <div v-if="trashCount === 0" class="empty-state">
        <a-empty description="回收站为空" />
      </div>

      <a-list v-else item-layout="horizontal" :data-source="noteStore.trashNotes" class="trash-list">
        <template #renderItem="{ item }">
          <a-list-item class="trash-item">
            <a-list-item-meta :description="`删除时间：${new Date(item.updatedAt).toLocaleString()}`">
              <template #title>
                <span class="note-title">{{ item.title || '无标题' }}</span>
                <span class="notebook-name" v-if="item.notebook">来自：{{ item.notebook.name }}</span>
              </template>
            </a-list-item-meta>

            <template #actions>
              <a-button type="link" @click="handleRestore(item.id)">
                <template #icon><ReloadOutlined /></template>
                恢复
              </a-button>
              <a-button type="link" danger @click="handleHardDelete(item.id)">
                彻底删除
              </a-button>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </section>
  </div>
</template>

<style scoped>
.trash-page {
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

.trash-summary,
.trash-panel {
  width: min(var(--sn-container-width), 100%);
  margin: 0 auto;
}

.trash-summary {
  grid-template-columns: minmax(0, 280px);
}

.trash-panel {
  padding: 8px 24px;
}

.empty-state {
  min-height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.trash-item {
  padding-block: 18px;
}

.note-title {
  font-size: 16px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.95);
}

.notebook-name {
  margin-left: 10px;
  color: #615d59;
  font-size: 13px;
}
</style>
