<script setup lang="ts">
import { onMounted, createVNode } from 'vue';
import { useNoteStore } from '../stores/note';
import { useRouter } from 'vue-router';
import { 
  ArrowLeftOutlined, 
  DeleteOutlined, 
  ReloadOutlined, 
  ExclamationCircleOutlined 
} from '@ant-design/icons-vue';
import { message, Modal } from 'ant-design-vue';

const noteStore = useNoteStore();
const router = useRouter();

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
    content: '彻底删除后将无法恢复，确认要删除该笔记吗？',
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
    }
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
    content: '清空后所有回收站中的笔记将无法恢复，确认清空吗？',
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
    }
  });
};
</script>

<template>
  <div class="trash-layout">
    <div class="header">
      <div style="display: flex; align-items: center; gap: 16px;">
        <a-button type="text" shape="circle" @click="router.push('/home')">
          <template #icon><ArrowLeftOutlined /></template>
        </a-button>
        <h2 style="margin: 0; display: flex; align-items: center; gap: 8px;">
          <DeleteOutlined /> 回收站
        </h2>
      </div>
      <a-button danger @click="handleEmptyTrash" :disabled="noteStore.trashNotes.length === 0">
        <template #icon><DeleteOutlined /></template>
        清空回收站
      </a-button>
    </div>

    <div class="content">
      <div v-if="noteStore.trashNotes.length === 0" class="empty-state">
        <a-empty description="回收站为空" />
      </div>
      
      <a-list v-else item-layout="horizontal" :data-source="noteStore.trashNotes" class="trash-list">
        <template #renderItem="{ item }">
          <a-list-item class="trash-item">
            <a-list-item-meta
              :description="`删除于: ${new Date(item.updatedAt).toLocaleString()}`"
            >
              <template #title>
                <span class="note-title">{{ item.title || '无标题' }}</span>
                <span class="notebook-name" v-if="item.notebook"> (来自: {{ item.notebook.name }})</span>
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
    </div>
  </div>
</template>

<style scoped>
.trash-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f0f2f5;
}

.header {
  background: #fff;
  padding: 16px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #e8e8e8;
}

.content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

.empty-state {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background: #fff;
  border-radius: 8px;
}

.trash-list {
  background: #fff;
  border-radius: 8px;
  padding: 0 24px;
}

.trash-item {
  transition: all 0.3s;
}

.trash-item:hover {
  background-color: #fafafa;
}

.note-title {
  font-size: 16px;
  font-weight: 500;
}

.notebook-name {
  color: #888;
  font-size: 12px;
}
</style>