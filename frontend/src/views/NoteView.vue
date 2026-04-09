<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useNoteStore } from '../stores/note';
import { useNotebookStore } from '../stores/notebook';
import { useTagStore } from '../stores/tag';
import { useFolderStore } from '../stores/folder';
import {
  PlusOutlined, SaveOutlined, TagOutlined, HistoryOutlined,
  RobotOutlined, MoreOutlined, CopyOutlined, DragOutlined,
  ArrowLeftOutlined, DeleteOutlined, ShareAltOutlined,
  DownloadOutlined, MessageOutlined, FolderOutlined,
  FolderOpenOutlined, EditOutlined, FolderAddOutlined,
} from '@ant-design/icons-vue';
import MarkdownEditor from '../components/MarkdownEditor.vue';
import AIAssistantDrawer from '../components/AIAssistantDrawer.vue';
import { message, Modal } from 'ant-design-vue';

const route = useRoute();
const router = useRouter();
const noteStore = useNoteStore();
const notebookStore = useNotebookStore();
const tagStore = useTagStore();
const folderStore = useFolderStore();

const notebookId = ref<number | null>(null);
const selectedNoteId = ref<number | null>(null);
const selectedTags = ref<string[]>([]);
const currentUsername = localStorage.getItem('displayName') || localStorage.getItem('username') || 'Author';

// 自动保存与状态标识
let autoSaveTimer: any = null;
const lastSavedTime = ref<string>('');
const isSwitchingNote = ref<boolean>(false);

const normalizeRouteId = (value: unknown) => {
  const rawValue = Array.isArray(value) ? value[0] : value;
  const id = Number(rawValue);
  return Number.isInteger(id) && id > 0 ? id : null;
};

const finishSwitchingNote = () => {
  window.setTimeout(() => {
    isSwitchingNote.value = false;
  }, 100);
};

const resetCurrentNoteState = () => {
  selectedNoteId.value = null;
  selectedTags.value = [];
  lastSavedTime.value = '';
  noteStore.currentNote = null;
};

const loadNotebookContext = async (targetNotebookId: number, preferredNoteId: number | null) => {
  notebookId.value = targetNotebookId;
  isSwitchingNote.value = true;

  if (autoSaveTimer) clearTimeout(autoSaveTimer);

  folderStore.clearFolders();
  resetCurrentNoteState();

  await Promise.all([
    noteStore.fetchNotes(targetNotebookId),
    folderStore.fetchFolders(targetNotebookId),
  ]);

  const selectedFromRoute = preferredNoteId != null && noteStore.notes.some((note) => note.id === preferredNoteId)
    ? preferredNoteId
    : null;
  const nextSelectedNoteId = selectedFromRoute ?? noteStore.notes[0]?.id ?? null;

  if (nextSelectedNoteId != null) {
    await handleSelectNote(nextSelectedNoteId);
    return;
  }

  finishSwitchingNote();
};

onMounted(() => {
  document.addEventListener('keydown', handleKeyDown);
  notebookStore.fetchNotebooks();
  tagStore.fetchTags();
});

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeyDown);
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
});

const handleSelectNote = async (id: number) => {
  if (!id) return;
  isSwitchingNote.value = true;
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  lastSavedTime.value = '';
  selectedNoteId.value = id;
  
  await noteStore.getNoteDetail(id);
  // 初始化选中的标签
  if (noteStore.currentNote && noteStore.currentNote.tags) {
    selectedTags.value = noteStore.currentNote.tags.map(t => t.name);
  } else {
    selectedTags.value = [];
  }

  if (
    notebookId.value != null
    && (
      normalizeRouteId(route.params.notebookId) !== notebookId.value
      || normalizeRouteId(route.query.noteId) !== id
    )
  ) {
    void router.replace({
      name: 'notebook',
      params: { notebookId: notebookId.value },
      query: { noteId: id },
    });
  }
  
  // 延迟解除切换状态，避免触发自动保存
  finishSwitchingNote();
};

const handleCreateNote = async () => {
  if (!notebookId.value) return;
  isSwitchingNote.value = true;
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  lastSavedTime.value = '';
  
  const newNote = await noteStore.createNote(notebookId.value);
  if (newNote) {
    selectedNoteId.value = newNote.id;
    selectedTags.value = [];
    message.success('新笔记已创建');
  }
  
  // 延迟解除切换状态，避免触发自动保存
  finishSwitchingNote();
};

watch(
  () => [normalizeRouteId(route.params.notebookId), normalizeRouteId(route.query.noteId)] as const,
  async ([nextNotebookId, nextNoteId]) => {
    if (nextNotebookId == null) return;

    if (notebookId.value !== nextNotebookId) {
      await loadNotebookContext(nextNotebookId, nextNoteId);
      return;
    }

    if (nextNoteId != null && nextNoteId !== selectedNoteId.value && noteStore.notes.some((note) => note.id === nextNoteId)) {
      await handleSelectNote(nextNoteId);
    }
  },
  { immediate: true }
);

const handleSave = async (isAutoSave = false) => {
  if (noteStore.currentNote) {
    await noteStore.updateNote(noteStore.currentNote.id, {
      title: noteStore.currentNote.title,
      content: noteStore.currentNote.content,
      contentHtml: noteStore.currentNote.contentHtml, // 确保发送 HTML
      tags: selectedTags.value, // 发送标签名称列表
      forceHistory: !isAutoSave // 如果是手动保存，强制生成历史版本
    });
    // 重新获取标签列表，因为可能有新创建的标签
    await tagStore.fetchTags();
    
    // 更新保存时间
    const now = new Date();
    lastSavedTime.value = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`;
    
    if (!isAutoSave) {
      message.success('保存成功');
    }
  }
};

// 监听内容变化自动保存（简单防抖）
watch(
  () => noteStore.currentNote?.content,
  () => {
    if (isSwitchingNote.value) return;
    
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => {
      handleSave(true);
    }, 3000); // 3 秒自动保存
  }
);

// 快捷键保存
const handleKeyDown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault(); // 阻止浏览器默认的保存网页行为
    
    // 如果是通过快捷键触发的，也视为手动保存，应当强制生成历史记录
    // 但是直接调用 handleSave(false) 会因为没有点击按钮，焦点还在编辑器内部
    // 编辑器可能还没有通过 blur 更新 contentHtml。
    // 为了保险，虽然当前内容已经通过 v-model 更新，但我们依然触发手动保存
    handleSave(false);
  }
};

// 历史版本相关方法
const historyDrawerVisible = ref(false);
const previewModalVisible = ref(false);
const previewHistory = ref<any>(null);

// 引入一个计数器来强制刷新 MarkdownEditor 组件
const editorKey = ref(0);

// AI 摘要相关状态
const isGeneratingSummary = ref(false);
const suggestingTags = ref(false);
const aiDrawerVisible = ref(false);

const handleGenerateSummary = async () => {
  if (!noteStore.currentNote) return;
  
  if (!noteStore.currentNote.content || noteStore.currentNote.content.trim() === '') {
    message.warning('笔记内容为空，无法生成摘要');
    return;
  }

  isGeneratingSummary.value = true;
  try {
    // 强制先保存一下当前内容，确保 AI 拿到的是最新内容
    await handleSave(true);
    await noteStore.generateSummary(noteStore.currentNote.id);
    message.success('智能摘要生成成功');
  } catch (error: any) {
    message.error(error.response?.data?.message || '摘要生成失败，请检查网络或配置');
  } finally {
    isGeneratingSummary.value = false;
  }
};

const handleSuggestTags = async () => {
  if (!noteStore.currentNote) return;
  
  if (!noteStore.currentNote.content || noteStore.currentNote.content.trim() === '') {
    message.warning('笔记内容为空，无法推荐标签');
    return;
  }

  suggestingTags.value = true;
  try {
    await handleSave(true);
    const tags = await noteStore.suggestTags(noteStore.currentNote.id);
    if (tags && tags.length > 0) {
      // 合并现有标签和推荐标签，去重
      const newTags = Array.from(new Set([...selectedTags.value, ...tags]));
      selectedTags.value = newTags;
      // 触发保存
      await handleSave(true);
      message.success(`成功推荐并添加了 ${tags.length} 个标签！`);
    } else {
      message.info('AI 未能提取出合适的标签');
    }
  } catch (error: any) {
    message.error(error.response?.data?.message || '标签推荐失败');
  } finally {
    suggestingTags.value = false;
  }
};

const openAIAssistant = () => {
  aiDrawerVisible.value = true;
};

const handleOpenHistory = async () => {
  if (!noteStore.currentNote) return;
  await noteStore.fetchNoteHistories(noteStore.currentNote.id);
  historyDrawerVisible.value = true;
};

const handlePreviewHistory = (history: any) => {
  previewHistory.value = history;
  previewModalVisible.value = true;
};

const handleRollbackHistory = () => {
  if (!noteStore.currentNote || !previewHistory.value) return;
  
  Modal.confirm({
    title: '确认回滚',
    content: '回滚后当前内容将被覆盖，同时会生成一条新的历史记录，是否继续？',
    onOk: async () => {
      const res = await noteStore.rollbackToHistory(noteStore.currentNote!.id, previewHistory.value.id);
      if (res) {
        message.success('回滚成功');
        previewModalVisible.value = false;
        historyDrawerVisible.value = false;
        
        // 强制重新渲染 MarkdownEditor 组件，让回滚后的内容立刻显示
        editorKey.value += 1;
      }
    }
  });
};

// 移动与复制笔记相关状态
const moveCopyModalVisible = ref(false);
const moveCopyActionType = ref<'move' | 'copy'>('move');
const targetNotebookId = ref<number | null>(null);

const handleOpenMoveCopyModal = (type: 'move' | 'copy') => {
  if (!noteStore.currentNote) return;
  moveCopyActionType.value = type;
  targetNotebookId.value = null;
  moveCopyModalVisible.value = true;
};

const submitMoveCopy = async () => {
  if (!noteStore.currentNote) return;
  if (!targetNotebookId.value) {
    message.warning('请选择目标笔记本');
    return;
  }
  
  try {
    if (moveCopyActionType.value === 'move') {
      await noteStore.moveNote(noteStore.currentNote.id, targetNotebookId.value);
      message.success('笔记移动成功');
      if (noteStore.notes.length > 0 && noteStore.notes[0]) {
        await handleSelectNote(noteStore.notes[0].id);
      } else {
        resetCurrentNoteState();
      }
    } else {
      await noteStore.copyNote(noteStore.currentNote.id, targetNotebookId.value);
      message.success('笔记复制成功');
    }
    moveCopyModalVisible.value = false;
  } catch (error: any) {
    message.error(error.response?.data?.message || '操作失败');
  }
};

const handleDeleteNote = () => {
  if (!noteStore.currentNote) return;
  Modal.confirm({
    title: '移至回收站',
    content: '笔记将移至回收站，可以在回收站中恢复或彻底删除，确认删除吗？',
    okText: '移至回收站',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await noteStore.deleteNote(noteStore.currentNote!.id);
        message.success('笔记已移至回收站');
        // Auto select another note if available
        if (noteStore.notes.length > 0 && noteStore.notes[0]) {
          handleSelectNote(noteStore.notes[0].id);
        }
      } catch (error: any) {
        message.error('删除失败');
      }
    }
  });
};
// 分享笔记相关状态
const shareModalVisible = ref(false);
const shareInfo = ref<any>(null);
const shareExpireDays = ref<number | undefined>(undefined);
const shareExtractionCode = ref<string>('');
const shareAllowComment = ref<boolean>(false);
const shareAllowEdit = ref<boolean>(false);
const shareUrlPrefix = `${window.location.origin}/share/`;

const handleOpenShareModal = async () => {
  if (!noteStore.currentNote) return;
  shareModalVisible.value = true;
  shareInfo.value = null;
  shareExpireDays.value = undefined;
  shareExtractionCode.value = '';
  shareAllowComment.value = false;
  shareAllowEdit.value = false;
  const existingShare = await noteStore.getShare(noteStore.currentNote.id);
  if (existingShare && existingShare.isActive) {
    if (!existingShare.expireAt || new Date(existingShare.expireAt) > new Date()) {
      shareInfo.value = existingShare;
    }
  }
};

const handleCreateShare = async () => {
  if (!noteStore.currentNote) return;
  try {
    const res = await noteStore.createShare(
      noteStore.currentNote.id, 
      shareExpireDays.value,
      shareExtractionCode.value,
      shareAllowComment.value,
      shareAllowEdit.value
    );
    shareInfo.value = res;
    message.success('分享链接生成成功');
  } catch (error: any) {
    message.error('生成失败');
  }
};

const handleDisableShare = async () => {
  if (!noteStore.currentNote) return;
  try {
    await noteStore.disableShare(noteStore.currentNote.id);
    shareInfo.value = null;
    message.success('分享已关闭');
  } catch (error: any) {
    message.error('关闭失败');
  }
};

const copyShareLink = () => {
  if (!shareInfo.value) return;
  const url = `${shareUrlPrefix}${shareInfo.value.token}`;
  navigator.clipboard.writeText(url).then(() => {
    message.success('链接已复制到剪贴板');
  });
};

const openCommentArea = () => {
  if (!noteStore.currentNote) {
    return;
  }
  router.push({
    name: 'comments',
    params: {
      notebookId: notebookId.value,
      noteId: noteStore.currentNote.id,
    },
  });
};

const handleExportMarkdown = async () => {
  if (!noteStore.currentNote) return;
  
  // 提示用户正在导出
  const hide = message.loading('正在导出...', 0);
  try {
    const success = await noteStore.exportNoteToMarkdown(
      noteStore.currentNote.id, 
      noteStore.currentNote.title || '未命名笔记'
    );
    if (success) {
      message.success('导出成功');
    } else {
      message.error('导出失败，请重试');
    }
  } finally {
    hide();
  }
};
const handleExportPdf = async () => {
  if (!noteStore.currentNote) return;

  const hide = message.loading('正在导出...', 0);
  try {
    const success = await noteStore.exportNoteToPdf(
      noteStore.currentNote.id,
      noteStore.currentNote.title || '未命名笔记'
    );
    if (success) {
      message.success('PDF 导出成功');
    } else {
      message.error('PDF 导出失败，请重试');
    }
  } finally {
    hide();
  }
};

const handleExportWord = async () => {
  if (!noteStore.currentNote) return;

  const hide = message.loading('正在导出...', 0);
  try {
    const success = await noteStore.exportNoteToWord(
      noteStore.currentNote.id,
      noteStore.currentNote.title || '未命名笔记'
    );
    if (success) {
      message.success('Word 导出成功');
    } else {
      message.error('Word 导出失败，请重试');
    }
  } finally {
    hide();
  }
};


// ──────────────────────────────────────────────────────────────────────────────
// 文件夹管理：状态与操作
// ──────────────────────────────────────────────────────────────────────────────

// 将笔记列表按文件夹分组
// 返回：{ root: Note[], folders: { folder, notes }[] }
const groupedNotes = computed(() => {
  const rootNotes = noteStore.notes.filter((n) => !n.folder && !n.folderId);
  const folderGroups = folderStore.folders.map((folder) => ({
    folder,
    notes: noteStore.notes.filter((n) => n.folder?.id === folder.id || n.folderId === folder.id),
  }));
  return { rootNotes, folderGroups };
});

// 文件夹管理：展开/折叠、分组、新建、重命名、删除、拖拽
// ──────────────────────────────────────────────────────────────────────────────

// 折叠状态：存储已折叠的文件夹 ID 列表（用数组保证 Vue 响应式）
const collapsedFolderIds = ref<number[]>([]);
const isCollapsed = (folderId: number) => collapsedFolderIds.value.includes(folderId);
const toggleFolder = (folderId: number) => {
  const idx = collapsedFolderIds.value.indexOf(folderId);
  if (idx >= 0) {
    collapsedFolderIds.value.splice(idx, 1); // 展开
  } else {
    collapsedFolderIds.value.push(folderId); // 折叠
  }
};

// 新建文件夹
const newFolderModalVisible = ref(false);
const newFolderName = ref('');
const handleOpenNewFolderModal = () => {
  newFolderName.value = '';
  newFolderModalVisible.value = true;
};
const handleCreateFolder = async () => {
  if (!notebookId.value) return;
  if (!newFolderName.value.trim()) {
    message.warning('文件夹名称不能为空');
    return;
  }
  try {
    await folderStore.createFolder(notebookId.value, newFolderName.value.trim());
    newFolderModalVisible.value = false;
    message.success('文件夹创建成功');
  } catch (error: any) {
    message.error(error.response?.data?.message || '创建文件夹失败');
  }
};

// 重命名文件夹（行内编辑）
const renamingFolder = ref<{ id: number; name: string } | null>(null);
const handleStartRename = (folder: { id: number; name: string }) => {
  renamingFolder.value = { ...folder };
};
const handleConfirmRename = async () => {
  if (!renamingFolder.value) return;
  if (!renamingFolder.value.name.trim()) {
    message.warning('文件夹名称不能为空');
    return;
  }
  try {
    await folderStore.renameFolder(renamingFolder.value.id, renamingFolder.value.name.trim());
    renamingFolder.value = null;
    message.success('重命名成功');
  } catch (error: any) {
    message.error(error.response?.data?.message || '重命名失败');
  }
};

// 删除文件夹（笔记移至根目录）
const handleDeleteFolder = (folderId: number, folderName: string) => {
  Modal.confirm({
    title: '删除文件夹',
    content: `确认删除「${folderName}」？文件夹内的笔记将移至根目录，不会丢失。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await folderStore.deleteFolder(folderId);
        noteStore.notes.forEach((n) => {
          if (n.folder?.id === folderId || n.folderId === folderId) {
            n.folder = null;
            n.folderId = null;
          }
        });
        if (noteStore.currentNote?.folder?.id === folderId || noteStore.currentNote?.folderId === folderId) {
          noteStore.currentNote.folder = null;
          noteStore.currentNote.folderId = null;
        }
        message.success('文件夹已删除，笔记已移至根目录');
      } catch (error: any) {
        message.error(error.response?.data?.message || '删除文件夹失败');
        throw error;
      }
    },
  });
};

// 拖拽笔记到文件夹：HTML5 拖拽 API
const draggingNoteId = ref<number | null>(null);

const handleNoteDragStart = (noteId: number) => {
  draggingNoteId.value = noteId;
};

const handleDropOnFolder = async (folderId: number | null) => {
  const noteId = draggingNoteId.value;
  draggingNoteId.value = null;
  if (noteId == null) return;

  try {
    await folderStore.moveNoteToFolder(noteId, folderId);
    const folder = folderId == null ? null : folderStore.folders.find((f) => f.id === folderId) ?? null;
    const nextFolder = folder ? { id: folder.id, name: folder.name } : null;
    const note = noteStore.notes.find((n) => n.id === noteId);

    if (note) {
      note.folder = nextFolder;
      note.folderId = folderId;
    }

    if (noteStore.currentNote?.id === noteId) {
      noteStore.currentNote.folder = nextFolder;
      noteStore.currentNote.folderId = folderId;
    }
  } catch (error: any) {
    message.error(error.response?.data?.message || '移动笔记失败');
  }
};

</script>

<template>
  <div class="note-editor-layout">
    <div class="note-list">
      <!-- 侧边栏头部：返回按钮 + 标题 + 新建按钮 -->
      <div class="list-header">
        <div style="display: flex; align-items: center; gap: 8px;">
          <a-button type="text" shape="circle" @click="router.push('/home')">
            <template #icon><ArrowLeftOutlined /></template>
          </a-button>
          <h3 style="margin: 0;">笔记列表</h3>
        </div>
        <div style="display: flex; gap: 6px;">
          <!-- 新建文件夹 -->
          <a-tooltip title="新建文件夹">
            <a-button type="text" shape="circle" size="small" @click="handleOpenNewFolderModal">
              <template #icon><FolderAddOutlined /></template>
            </a-button>
          </a-tooltip>
          <!-- 新建笔记 -->
          <a-button type="primary" shape="circle" size="small" @click="handleCreateNote">
            <template #icon><PlusOutlined /></template>
          </a-button>
        </div>
      </div>

      <!-- 笔记列表：按文件夹分组 -->
      <div
        class="list-content"
        @dragover.prevent
        @drop="handleDropOnFolder(null)"
      >
        <!-- 根目录笔记（无文件夹） -->
        <template v-if="groupedNotes.rootNotes.length > 0 || folderStore.folders.length === 0">
          <div
            v-for="item in groupedNotes.rootNotes"
            :key="item.id"
            class="note-item"
            :class="{ active: item.id === selectedNoteId }"
            draggable="true"
            @click="handleSelectNote(item.id)"
            @dragstart="handleNoteDragStart(item.id)"
          >
            <span class="note-title">{{ item.title || '未命名笔记' }}</span>
            <span class="note-date">{{ new Date(item.updatedAt).toLocaleDateString() }}</span>
          </div>
        </template>

        <!-- 各文件夹分组 -->
        <div
          v-for="{ folder, notes } in groupedNotes.folderGroups"
          :key="folder.id"
          class="folder-group"
          @dragover.prevent
          @drop.stop="handleDropOnFolder(folder.id)"
        >
          <!-- 文件夹标题行 -->
          <div class="folder-header" @click="toggleFolder(folder.id)">
            <!-- 展开/折叠图标 -->
            <component
              :is="isCollapsed(folder.id) ? FolderOutlined : FolderOpenOutlined"
              class="folder-icon"
            />
            <!-- 重命名中：行内输入框 -->
            <a-input
              v-if="renamingFolder?.id === folder.id"
              v-model:value="renamingFolder.name"
              size="small"
              class="folder-rename-input"
              @click.stop
              @pressEnter="handleConfirmRename"
              @blur="handleConfirmRename"
            />
            <span v-else class="folder-name">{{ folder.name }}</span>
            <span class="folder-count">{{ notes.length }}</span>

            <!-- 文件夹操作按钮（hover 显示） -->
            <div class="folder-actions" @click.stop>
              <a-tooltip title="重命名">
                <a-button
                  type="text" size="small" shape="circle"
                  @click="handleStartRename(folder)"
                >
                  <template #icon><EditOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="删除文件夹">
                <a-button
                  type="text" size="small" shape="circle" danger
                  @click="handleDeleteFolder(folder.id, folder.name)"
                >
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </a-tooltip>
            </div>
          </div>

          <!-- 文件夹内的笔记列表（可折叠） -->
          <div v-show="!isCollapsed(folder.id)" class="folder-notes">
            <div
              v-for="item in notes"
              :key="item.id"
              class="note-item"
              :class="{ active: item.id === selectedNoteId }"
              draggable="true"
              @click="handleSelectNote(item.id)"
              @dragstart="handleNoteDragStart(item.id)"
            >
              <span class="note-title">{{ item.title || '未命名笔记' }}</span>
              <span class="note-date">{{ new Date(item.updatedAt).toLocaleDateString() }}</span>
            </div>
            <!-- 文件夹为空时的提示 -->
            <div v-if="notes.length === 0" class="folder-empty">拖拽笔记到此处</div>
          </div>
        </div>
      </div>

      <!-- 新建文件夹弹窗 -->
      <a-modal
        v-model:open="newFolderModalVisible"
        title="新建文件夹"
        ok-text="创建"
        cancel-text="取消"
        @ok="handleCreateFolder"
      >
        <a-input
          v-model:value="newFolderName"
          placeholder="文件夹名称"
          @pressEnter="handleCreateFolder"
        />
      </a-modal>
    </div>

    <div v-if="noteStore.currentNote" class="editor-area">
      <div class="editor-header">
        <div class="header-top">
          <a-input
            v-model:value="noteStore.currentNote.title"
            class="title-input"
            placeholder="请输入标题"
            :bordered="false"
          />
          <span v-if="lastSavedTime" class="save-status">
            自动保存于 {{ lastSavedTime }}
          </span>
          <a-button
            type="dashed"
            class="editor-action-btn"
            @click="handleGenerateSummary"
            :loading="isGeneratingSummary"
          >
            <template #icon><RobotOutlined /></template>
            智能摘要
          </a-button>
          <a-button type="default" class="editor-action-btn" @click="openAIAssistant">
            <template #icon><RobotOutlined /></template>
            AI 问答
          </a-button>
          <a-button type="default" class="editor-action-btn" @click="handleOpenHistory">
            <template #icon><HistoryOutlined /></template>
            历史
          </a-button>
          <a-button type="default" class="editor-action-btn" @click="handleOpenShareModal">
            <template #icon><ShareAltOutlined /></template>
            分享
          </a-button>
          <a-button type="default" class="editor-action-btn" @click="openCommentArea">
            <template #icon><MessageOutlined /></template>
            评论区
          </a-button>
          <a-button type="primary" class="editor-action-btn" @click="() => handleSave(false)">
            <template #icon><SaveOutlined /></template>
            保存
          </a-button>
          <a-dropdown>
            <template #overlay>
              <a-menu>
                <a-menu-item key="move" @click="handleOpenMoveCopyModal('move')">
                  <DragOutlined /> 移动到...
                </a-menu-item>
                <a-menu-item key="copy" @click="handleOpenMoveCopyModal('copy')">
                  <CopyOutlined /> 复制到...
                </a-menu-item>
                <a-menu-item key="export" @click="handleExportMarkdown">
                  <DownloadOutlined /> 导出为 Markdown
                </a-menu-item>
                <a-menu-item key="export-pdf" @click="handleExportPdf">
                  <DownloadOutlined /> 导出为 PDF
                </a-menu-item>
                <a-menu-item key="export-word" @click="handleExportWord">
                  <DownloadOutlined /> 导出为 Word
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item key="delete" @click="handleDeleteNote" style="color: #ff4d4f;">
                  <DeleteOutlined /> 删除笔记
                </a-menu-item>
              </a-menu>
            </template>
            <a-button>
              <MoreOutlined />
            </a-button>
          </a-dropdown>
        </div>
        <div class="header-tags">
          <a-select
            v-model:value="selectedTags"
            mode="tags"
            style="flex: 1"
            placeholder="添加标签..."
            :options="tagStore.tags.map(t => ({ value: t.name, label: t.name }))"
          >
            <template #suffixIcon><TagOutlined /></template>
          </a-select>
          <a-button type="dashed" class="tag-suggest-btn" @click="handleSuggestTags" :loading="suggestingTags" title="智能推荐标签">
            <template #icon><RobotOutlined /></template>
            智能推荐
          </a-button>
        </div>

        <div v-if="noteStore.currentNote.summary" class="summary-area">
          <a-alert
            message="AI 智能摘要"
            :description="noteStore.currentNote.summary"
            type="info"
            show-icon
            closable
            @close="noteStore.currentNote.summary = ''"
          >
            <template #icon><RobotOutlined /></template>
          </a-alert>
        </div>
      </div>
      <div class="editor-content">
        <MarkdownEditor
          :key="`editor-${noteStore.currentNote.id}-${editorKey}`"
          v-model="noteStore.currentNote.content"
          :noteId="noteStore.currentNote.id"
          :collab="true"
          :currentUser="currentUsername"
          @update:contentHtml="html => noteStore.currentNote!.contentHtml = html"
        />
      </div>
    </div>
    <div v-else class="empty-state">
      <a-empty description="选择或创建一篇笔记开始编辑" />
    </div>

    <a-drawer
      title="历史版本"
      placement="right"
      :closable="true"
      v-model:open="historyDrawerVisible"
      width="300"
    >
      <a-list item-layout="horizontal" :data-source="noteStore.noteHistories">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta :description="new Date(item.savedAt).toLocaleString()">
              <template #title>
                <a @click="handlePreviewHistory(item)">{{ item.title || '未命名笔记' }}</a>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>
    </a-drawer>

    <a-modal
      v-model:open="previewModalVisible"
      title="历史版本预览"
      width="800px"
      :bodyStyle="{ padding: '0' }"
      @ok="handleRollbackHistory"
      ok-text="恢复此版本"
      cancel-text="关闭"
    >
      <div v-if="previewHistory" class="history-preview-content">
        <h3 style="margin-top: 0;">{{ previewHistory.title }}</h3>
        <div class="markdown-body" v-html="previewHistory.contentHtml || '<i>暂无内容展示</i>'"></div>
      </div>
    </a-modal>

    <a-modal
      v-model:open="moveCopyModalVisible"
      :title="moveCopyActionType === 'move' ? '移动笔记' : '复制笔记'"
      @ok="submitMoveCopy"
      ok-text="确认"
      cancel-text="取消"
    >
      <div style="padding: 20px 0;">
        <div style="margin-bottom: 8px;">选择目标笔记本：</div>
        <a-select
          v-model:value="targetNotebookId"
          style="width: 100%"
          placeholder="请选择笔记本"
          :options="notebookStore.notebooks.map(nb => ({ value: nb.id, label: nb.name }))"
        />
      </div>
    </a-modal>

    <a-modal
      v-model:open="shareModalVisible"
      title="分享笔记"
      :footer="null"
    >
      <div style="padding: 20px 0;">
        <div v-if="!shareInfo">
          <div style="margin-bottom: 16px;">
            <span style="display: inline-block; width: 80px;">有效期：</span>
            <a-select v-model:value="shareExpireDays" style="width: 200px">
              <a-select-option :value="undefined">永久有效</a-select-option>
              <a-select-option :value="1">1 天</a-select-option>
              <a-select-option :value="7">7 天</a-select-option>
              <a-select-option :value="30">30 天</a-select-option>
            </a-select>
          </div>
          <div style="margin-bottom: 16px;">
            <span style="display: inline-block; width: 80px;">提取码：</span>
            <a-input v-model:value="shareExtractionCode" placeholder="选填，留空则公开访问" style="width: 200px" />
          </div>
          <div style="margin-bottom: 16px;">
            <span style="display: inline-block; width: 80px;">允许评论：</span>
            <a-switch v-model:checked="shareAllowComment" />
          </div>
          <div style="margin-bottom: 24px;">
            <span style="display: inline-block; width: 80px;">协同编辑：</span>
            <a-switch v-model:checked="shareAllowEdit" />
          </div>
          <a-button type="primary" block @click="handleCreateShare">生成分享链接</a-button>
        </div>
        <div v-else>
          <a-alert
            message="分享链接已生成"
            type="success"
            show-icon
            style="margin-bottom: 16px;"
          />
          <div style="display: flex; gap: 8px; margin-bottom: 16px;">
            <a-input :value="`${shareUrlPrefix}${shareInfo.token}`" readonly />
            <a-button type="primary" @click="copyShareLink">复制</a-button>
          </div>
          <div v-if="shareInfo.extractionCode" style="color: #666; margin-bottom: 8px;">
            提取码：<span style="font-weight: bold; color: #1890ff;">{{ shareInfo.extractionCode }}</span>
          </div>
          <div style="color: #666; margin-bottom: 8px;">
            允许评论：{{ shareInfo.allowComment ? '是' : '否' }}
          </div>
          <div style="color: #666; margin-bottom: 8px;">
            协同编辑：{{ shareInfo.allowEdit ? '是' : '否' }}
          </div>
          <div style="color: #999; margin-bottom: 16px;">
            过期时间：{{ shareInfo.expireAt ? new Date(shareInfo.expireAt).toLocaleString() : '永久有效' }}
          </div>
          <a-button danger block @click="handleDisableShare">关闭分享</a-button>
        </div>
      </div>
    </a-modal>

    <AIAssistantDrawer
      v-model:visible="aiDrawerVisible"
      :current-note-id="noteStore.currentNote?.id ?? null"
      :current-note-title="noteStore.currentNote?.title ?? ''"
    />
  </div>
</template>

<style scoped>
.note-editor-layout {
  display: flex;
  min-height: 100vh;
  overflow: hidden;
  background: linear-gradient(180deg, #fbfaf8 0%, #f6f5f4 100%);
}

.note-list {
  width: 288px;
  padding: 18px 12px;
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.list-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.88);
  flex-shrink: 0;
  box-shadow: var(--sn-shadow-card);
}

.list-content {
  flex: 1;
  overflow-y: auto;
  margin-top: 14px;
  padding: 10px 8px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.82);
}

.note-item {
  cursor: pointer;
  padding: 10px 14px;
  transition: background-color 0.15s, transform 0.15s ease, border-color 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-radius: 14px;
  border: 1px solid transparent;
  user-select: none;
}

.note-item:hover {
  background-color: rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}

.note-item.active {
  background-color: #f2f9ff;
  border-color: rgba(0, 117, 222, 0.16);
}

.note-title {
  font-weight: 600;
  color: rgba(0, 0, 0, 0.95);
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.note-date {
  font-size: 12px;
  color: #615d59;
}

.folder-group {
  margin-bottom: 4px;
}

.folder-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  cursor: pointer;
  border-radius: 14px;
  margin: 0 2px;
  transition: background-color 0.15s, border-color 0.15s ease;
  position: relative;
  border: 1px solid transparent;
}

.folder-header:hover {
  background-color: rgba(0, 0, 0, 0.04);
  border-color: rgba(0, 0, 0, 0.06);
}

.folder-icon {
  font-size: 14px;
  color: #dd5b00;
  flex-shrink: 0;
}

.folder-name {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.95);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-count {
  font-size: 11px;
  color: #615d59;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 999px;
  padding: 2px 8px;
  flex-shrink: 0;
}

.folder-actions {
  display: none;
  align-items: center;
  gap: 4px;
}

.folder-header:hover .folder-actions {
  display: flex;
}

.folder-rename-input {
  flex: 1;
  font-size: 13px;
}

.folder-notes {
  padding-left: 16px;
}

.folder-empty {
  font-size: 12px;
  color: #a39e98;
  padding: 6px 16px;
  font-style: italic;
}

.editor-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  min-width: 0;
  overflow: hidden;
  padding: 18px 18px 18px 16px;
}

.editor-header {
  padding: 20px 24px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 24px 24px 0 0;
  background:
    radial-gradient(circle at top right, rgba(0, 117, 222, 0.08), transparent 26%),
    linear-gradient(180deg, #ffffff 0%, #fbfaf8 100%);
  display: flex;
  flex-direction: column;
  box-shadow: var(--sn-shadow-card);
}

.header-top {
  width: 100%;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.editor-action-btn {
  margin-right: 0 !important;
}

.title-input {
  font-size: 28px;
  font-weight: 700;
  flex: 1;
}

.save-status {
  font-size: 12px;
  color: #615d59;
  margin-right: 16px;
  user-select: none;
}

.header-tags {
  width: 100%;
  padding: 0 12px;
  display: flex;
  gap: 8px;
}

.tag-suggest-btn {
  white-space: nowrap;
}

.summary-area {
  margin: 12px 12px 0;
}

.editor-content {
  flex: 1;
  overflow: hidden;
  padding: 0;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-top: none;
  border-radius: 0 0 24px 24px;
  background: #ffffff;
  box-shadow: var(--sn-shadow-card);
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 18px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--sn-shadow-card);
}

.history-preview-content {
  max-height: 60vh;
  overflow-y: auto;
  padding: 24px;
  background-color: #fbfaf8;
  border-radius: 16px;
}

.markdown-body img {
  max-width: 100%;
}

@media (max-width: 1100px) {
  .note-list {
    width: 260px;
  }

  .editor-area {
    padding-left: 12px;
  }

  .header-tags {
    flex-direction: column;
  }
}

@media (max-width: 860px) {
  .note-editor-layout {
    flex-direction: column;
    height: auto;
  }

  .note-list {
    width: 100%;
    max-height: 42vh;
    padding-bottom: 0;
    border-right: none;
    border-bottom: 1px solid rgba(0, 0, 0, 0.08);
  }

  .editor-area {
    min-height: 0;
    padding: 16px;
  }

  .editor-header {
    border-radius: 20px 20px 0 0;
  }

  .editor-content {
    border-radius: 0 0 20px 20px;
  }
}
</style>

