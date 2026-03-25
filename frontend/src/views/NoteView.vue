<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useNoteStore, type ShareComment } from '../stores/note';
import { useNotebookStore } from '../stores/notebook';
import { useTagStore } from '../stores/tag';
import { PlusOutlined, SaveOutlined, TagOutlined, HistoryOutlined, RobotOutlined, MoreOutlined, CopyOutlined, DragOutlined, ArrowLeftOutlined, DeleteOutlined, ShareAltOutlined, DownloadOutlined } from '@ant-design/icons-vue';
import MarkdownEditor from '../components/MarkdownEditor.vue';
import { message, Modal } from 'ant-design-vue';

const route = useRoute();
const router = useRouter();
const noteStore = useNoteStore();
const notebookStore = useNotebookStore();
const tagStore = useTagStore();

const notebookId = ref(Number(route.params.notebookId));
const selectedNoteId = ref<number | null>(null);
const selectedTags = ref<string[]>([]);
const currentUsername = localStorage.getItem('displayName') || localStorage.getItem('username') || 'Author';

// 自动保存与状态标志
let autoSaveTimer: any = null;
const lastSavedTime = ref<string>('');
const isSwitchingNote = ref<boolean>(false);

onMounted(async () => {
  document.addEventListener('keydown', handleKeyDown);
  
  if (notebookId.value) {
    await noteStore.fetchNotes(notebookId.value);
    
    // 如果 URL 参数中有 noteId，则优先选中该笔记
    const noteIdFromQuery = Number(route.query.noteId);
    if (noteIdFromQuery) {
        handleSelectNote(noteIdFromQuery);
    } else if (noteStore.notes.length > 0 && noteStore.notes[0]) {
      handleSelectNote(noteStore.notes[0].id);
    }
  }
  // 异步加载其他数据，不阻塞主流程
  notebookStore.fetchNotebooks();
  tagStore.fetchTags();
});

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeyDown);
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
  
  // 延迟解除切换状态，避免触发自动保存
  setTimeout(() => { isSwitchingNote.value = false; }, 100);
};

const handleCreateNote = async () => {
  isSwitchingNote.value = true;
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  lastSavedTime.value = '';
  
  const newNote = await noteStore.createNote(notebookId.value);
  if (newNote) {
    selectedNoteId.value = newNote.id;
    selectedTags.value = [];
    message.success('新笔记已创建');
  }
  
  // 延迟解除切换状态
  setTimeout(() => { isSwitchingNote.value = false; }, 100);
};

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

// 监听内容变化自动保存 (简单的防抖)
watch(
  () => noteStore.currentNote?.content,
  () => {
    if (isSwitchingNote.value) return;
    
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => {
      handleSave(true);
    }, 3000); // 3秒自动保存
  }
);

// 快捷键保存
const handleKeyDown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault(); // 阻止浏览器默认的保存网页行为
    
    // 如果是通过快捷键触发的，也视为手动保存，应当强制生成历史记录
    // 但是直接调用 handleSave(false) 会因为没有点击按钮，焦点还在编辑器内部
    // Vditor 可能没有触发 blur 更新 contentHtml。
    // 为了保险，虽然当前内容已经通过 v-model 更新，但我们依然触发手动保存标识
    handleSave(false);
  }
};

// 历史版本相关方法
const historyDrawerVisible = ref(false);
const previewModalVisible = ref(false);
const previewHistory = ref<any>(null);

// 引入一个计数器来强制刷新 Vditor 编辑器组件
const editorKey = ref(0);

// AI 摘要相关状态
const isGeneratingSummary = ref(false);
const suggestingTags = ref(false);

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
    message.success('智能摘要生成成功！');
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
      // If no notes left or current is gone, select first
      if (!noteStore.currentNote && noteStore.notes.length > 0 && noteStore.notes[0]) {
          handleSelectNote(noteStore.notes[0].id);
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
const shareComments = ref<ShareComment[]>([]);
const shareCommentsLoading = ref(false);

const formatShareCommentTime = (value: string) => new Date(value).toLocaleString('zh-CN');

const loadShareComments = async (noteId: number) => {
  shareCommentsLoading.value = true;
  try {
    shareComments.value = await noteStore.getShareComments(noteId);
  } catch (error: any) {
    shareComments.value = [];
    message.error(error.response?.data?.message || '评论加载失败');
  } finally {
    shareCommentsLoading.value = false;
  }
};

const handleOpenShareModal = async () => {
  if (!noteStore.currentNote) return;
  shareModalVisible.value = true;
  shareInfo.value = null;
  shareExpireDays.value = undefined;
  shareExtractionCode.value = '';
  shareAllowComment.value = false;
  shareAllowEdit.value = false;
  shareComments.value = [];
  // 检查是否已有分享
  const existingShare = await noteStore.getShare(noteStore.currentNote.id);
  if (existingShare && existingShare.isActive) {
    // 检查是否已过期，如果已过期则不显示已存在的链接，允许重新生成
    if (!existingShare.expireAt || new Date(existingShare.expireAt) > new Date()) {
      shareInfo.value = existingShare;
      await loadShareComments(noteStore.currentNote.id);
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
    await loadShareComments(noteStore.currentNote.id);
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
    shareComments.value = [];
    message.success('分享已关闭');
  } catch (error: any) {
    message.error('关闭失败');
  }
};

const copyShareLink = () => {
  if (!shareInfo.value) return;
  const url = `${shareUrlPrefix.value}${shareInfo.value.token}`;
  navigator.clipboard.writeText(url).then(() => {
    message.success('链接已复制到剪贴板');
  });
};

const handleExportMarkdown = async () => {
  if (!noteStore.currentNote) return;
  
  // 提示用户正在导出
  const hide = message.loading('正在导出...', 0);
  try {
    const success = await noteStore.exportNoteToMarkdown(
      noteStore.currentNote.id, 
      noteStore.currentNote.title || '无标题笔记'
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

const shareUrlPrefix = ref(window.location.origin + '/share/');

</script>

<template>
  <div class="note-editor-layout">
    <div class="note-list">
      <div class="list-header">
        <div style="display: flex; align-items: center; gap: 8px;">
          <a-button type="text" shape="circle" @click="router.push('/home')">
            <template #icon><ArrowLeftOutlined /></template>
          </a-button>
          <h3 style="margin: 0;">笔记列表</h3>
        </div>
        <a-button type="primary" shape="circle" size="small" @click="handleCreateNote">
          <template #icon><PlusOutlined /></template>
        </a-button>
      </div>
      <div class="list-content" style="flex: 1; overflow-y: auto;">
        <a-list item-layout="horizontal" :data-source="noteStore.notes">
          <template #renderItem="{ item }">
            <a-list-item
              class="note-item"
              :class="{ active: item.id === selectedNoteId }"
              @click="handleSelectNote(item.id)"
            >
              <a-list-item-meta :description="new Date(item.updatedAt).toLocaleDateString()">
                <template #title>
                  <span class="note-title">{{ item.title || '无标题' }}</span>
                </template>
              </a-list-item-meta>
            </a-list-item>
          </template>
        </a-list>
      </div>
    </div>
    
    <div class="editor-area" v-if="noteStore.currentNote">
      <div class="editor-header">
        <div class="header-top" style="width: 100%; display: flex; align-items: center; margin-bottom: 10px;">
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
              @click="handleGenerateSummary" 
              style="margin-right: 8px; color: #722ed1; border-color: #722ed1;"
              :loading="isGeneratingSummary"
            >
              <template #icon><RobotOutlined /></template>
              智能摘要
            </a-button>
            <a-button type="default" @click="handleOpenHistory" style="margin-right: 8px;">
              <template #icon><HistoryOutlined /></template>
              历史
            </a-button>
            <a-button type="default" @click="handleOpenShareModal" style="margin-right: 8px;">
              <template #icon><ShareAltOutlined /></template>
              分享
            </a-button>
            <a-button type="primary" @click="() => handleSave(false)" style="margin-right: 8px;">
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
        <div class="header-tags" style="width: 100%; padding: 0 12px; display: flex; gap: 8px;">
             <a-select
              v-model:value="selectedTags"
              mode="tags"
              style="flex: 1"
              placeholder="添加标签..."
              :options="tagStore.tags.map(t => ({ value: t.name, label: t.name }))"
            >
              <template #suffixIcon><tag-outlined /></template>
            </a-select>
            <a-button type="dashed" @click="handleSuggestTags" :loading="suggestingTags" title="智能推荐标签">
              <template #icon><RobotOutlined /></template>
              智能推荐
            </a-button>
        </div>
        
        <!-- AI 摘要展示区 -->
        <div v-if="noteStore.currentNote.summary" class="summary-area" style="margin: 12px 12px 0 12px;">
          <a-alert
            message="✨ AI 智能摘要"
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
    <div class="empty-state" v-else>
      <a-empty description="选择或创建一个笔记开始编辑" />
    </div>

    <!-- 历史版本抽屉 -->
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
                <a @click="handlePreviewHistory(item)">{{ item.title || '无标题' }}</a>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>
    </a-drawer>

    <!-- 历史版本预览弹窗 -->
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

    <!-- 移动/复制笔记弹窗 -->
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

    <!-- 分享笔记弹窗 -->
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
              <a-select-option :value="1">1天</a-select-option>
              <a-select-option :value="7">7天</a-select-option>
              <a-select-option :value="30">30天</a-select-option>
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
          <div style="color: #666; margin-bottom: 8px;" v-if="shareInfo.extractionCode">
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
          <div class="share-comments-panel">
            <div class="share-comments-head">
              <div>
                <div class="share-comments-title">分享评论</div>
                <div class="share-comments-subtitle">作者侧可直接查看这条分享下的全部评论与锚点位置。</div>
              </div>
              <a-tag color="processing">{{ shareComments.length }} 条</a-tag>
            </div>
            <a-spin :spinning="shareCommentsLoading">
              <a-empty
                v-if="shareComments.length === 0"
                :description="shareInfo.allowComment ? '暂时还没有评论' : '当前分享未开启评论'"
              />
              <div v-else class="share-comment-list">
                <div v-for="comment in shareComments" :key="comment.id" class="share-comment-item">
                  <div class="share-comment-meta">
                    <span class="share-comment-author">{{ comment.authorName }}</span>
                    <span>{{ formatShareCommentTime(comment.createdAt) }}</span>
                  </div>
                  <div v-if="comment.anchorLabel" class="share-comment-anchor">
                    {{ comment.anchorLabel }}
                  </div>
                  <div v-if="comment.anchorPreview" class="share-comment-preview">
                    {{ comment.anchorPreview }}
                  </div>
                  <div class="share-comment-content">{{ comment.content }}</div>
                </div>
              </div>
            </a-spin>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.note-editor-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: #fff;
}

.note-list {
  width: 250px;
  border-right: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
}

.list-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f0f0f0;
}

.note-item {
  cursor: pointer;
  padding: 12px 16px;
  transition: background-color 0.3s;
}

.note-item:hover {
  background-color: #f5f5f5;
}

.note-item.active {
  background-color: #e6f7ff;
  border-right: 2px solid #1890ff;
}

.note-title {
  font-weight: 500;
  color: #333;
}

.editor-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  overflow: hidden;
}

.editor-header {
  padding: 16px 24px;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
}

.title-input {
  font-size: 24px;
  font-weight: bold;
  flex: 1;
}

.save-status {
  font-size: 12px;
  color: #999;
  margin-right: 16px;
  user-select: none;
}

.editor-content {
  flex: 1;
  overflow: hidden;
  padding: 0;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.history-preview-content {
  max-height: 60vh;
  overflow-y: auto;
  padding: 24px;
  background-color: #f9f9f9;
  border-radius: 4px;
}

.share-comments-panel {
  margin-top: 20px;
  padding: 16px;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  background: #fafcff;
}

.share-comments-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.share-comments-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f1f1f;
}

.share-comments-subtitle {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #8c8c8c;
}

.share-comment-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.share-comment-item {
  padding: 14px 16px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #eef2f6;
}

.share-comment-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.share-comment-author {
  font-size: 13px;
  font-weight: 600;
  color: #1677ff;
}

.share-comment-anchor {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 500;
  color: #0958d9;
}

.share-comment-preview {
  margin-bottom: 8px;
  padding: 10px 12px;
  border-left: 3px solid #91caff;
  border-radius: 6px;
  background: #f0f7ff;
  font-size: 12px;
  line-height: 1.6;
  color: #595959;
}

.share-comment-content {
  white-space: pre-wrap;
  line-height: 1.7;
  color: #262626;
}

/* 保证 Markdown 内容的图片等不会超出边界 */
.markdown-body img {
  max-width: 100%;
}
</style>
