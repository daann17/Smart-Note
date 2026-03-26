<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useNoteStore } from '../stores/note';
import { useNotebookStore } from '../stores/notebook';
import { useTagStore } from '../stores/tag';
import { PlusOutlined, SaveOutlined, TagOutlined, HistoryOutlined, RobotOutlined, MoreOutlined, CopyOutlined, DragOutlined, ArrowLeftOutlined, DeleteOutlined, ShareAltOutlined, DownloadOutlined, MessageOutlined } from '@ant-design/icons-vue';
import MarkdownEditor from '../components/MarkdownEditor.vue';
import AIAssistantDrawer from '../components/AIAssistantDrawer.vue';
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

// 鑷姩淇濆瓨涓庣姸鎬佹爣蹇?
let autoSaveTimer: any = null;
const lastSavedTime = ref<string>('');
const isSwitchingNote = ref<boolean>(false);

onMounted(async () => {
  document.addEventListener('keydown', handleKeyDown);
  
  if (notebookId.value) {
    await noteStore.fetchNotes(notebookId.value);
    
    // 濡傛灉 URL 鍙傛暟涓湁 noteId锛屽垯浼樺厛閫変腑璇ョ瑪璁?
    const noteIdFromQuery = Number(route.query.noteId);
    if (noteIdFromQuery) {
        handleSelectNote(noteIdFromQuery);
    } else if (noteStore.notes.length > 0 && noteStore.notes[0]) {
      handleSelectNote(noteStore.notes[0].id);
    }
  }
  // 寮傛鍔犺浇鍏朵粬鏁版嵁锛屼笉闃诲涓绘祦绋?
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
  // 鍒濆鍖栭€変腑鐨勬爣绛?
  if (noteStore.currentNote && noteStore.currentNote.tags) {
    selectedTags.value = noteStore.currentNote.tags.map(t => t.name);
  } else {
    selectedTags.value = [];
  }
  
  // 寤惰繜瑙ｉ櫎鍒囨崲鐘舵€侊紝閬垮厤瑙﹀彂鑷姩淇濆瓨
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
    message.success('鏂扮瑪璁板凡鍒涘缓');
  }
  
  // 寤惰繜瑙ｉ櫎鍒囨崲鐘舵€?
  setTimeout(() => { isSwitchingNote.value = false; }, 100);
};

const handleSave = async (isAutoSave = false) => {
  if (noteStore.currentNote) {
    await noteStore.updateNote(noteStore.currentNote.id, {
      title: noteStore.currentNote.title,
      content: noteStore.currentNote.content,
      contentHtml: noteStore.currentNote.contentHtml, // 纭繚鍙戦€?HTML
      tags: selectedTags.value, // 鍙戦€佹爣绛惧悕绉板垪琛?
      forceHistory: !isAutoSave // 濡傛灉鏄墜鍔ㄤ繚瀛橈紝寮哄埗鐢熸垚鍘嗗彶鐗堟湰
    });
    // 閲嶆柊鑾峰彇鏍囩鍒楄〃锛屽洜涓哄彲鑳芥湁鏂板垱寤虹殑鏍囩
    await tagStore.fetchTags();
    
    // 鏇存柊淇濆瓨鏃堕棿
    const now = new Date();
    lastSavedTime.value = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`;
    
    if (!isAutoSave) {
      message.success('淇濆瓨鎴愬姛');
    }
  }
};

// 鐩戝惉鍐呭鍙樺寲鑷姩淇濆瓨 (绠€鍗曠殑闃叉姈)
watch(
  () => noteStore.currentNote?.content,
  () => {
    if (isSwitchingNote.value) return;
    
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => {
      handleSave(true);
    }, 3000); // 3绉掕嚜鍔ㄤ繚瀛?
  }
);

// 蹇嵎閿繚瀛?
const handleKeyDown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault(); // 闃绘娴忚鍣ㄩ粯璁ょ殑淇濆瓨缃戦〉琛屼负
    
    // 濡傛灉鏄€氳繃蹇嵎閿Е鍙戠殑锛屼篃瑙嗕负鎵嬪姩淇濆瓨锛屽簲褰撳己鍒剁敓鎴愬巻鍙茶褰?
    // 浣嗘槸鐩存帴璋冪敤 handleSave(false) 浼氬洜涓烘病鏈夌偣鍑绘寜閽紝鐒︾偣杩樺湪缂栬緫鍣ㄥ唴閮?
    // Vditor 鍙兘娌℃湁瑙﹀彂 blur 鏇存柊 contentHtml銆?
    // 涓轰簡淇濋櫓锛岃櫧鐒跺綋鍓嶅唴瀹瑰凡缁忛€氳繃 v-model 鏇存柊锛屼絾鎴戜滑渚濈劧瑙﹀彂鎵嬪姩淇濆瓨鏍囪瘑
    handleSave(false);
  }
};

// 鍘嗗彶鐗堟湰鐩稿叧鏂规硶
const historyDrawerVisible = ref(false);
const previewModalVisible = ref(false);
const previewHistory = ref<any>(null);

// 寮曞叆涓€涓鏁板櫒鏉ュ己鍒跺埛鏂?Vditor 缂栬緫鍣ㄧ粍浠?
const editorKey = ref(0);

// AI 鎽樿鐩稿叧鐘舵€?
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
    // 寮哄埗鍏堜繚瀛樹竴涓嬪綋鍓嶅唴瀹癸紝纭繚 AI 鎷垮埌鐨勬槸鏈€鏂板唴瀹?
    await handleSave(true);
    await noteStore.generateSummary(noteStore.currentNote.id);
    message.success('智能摘要生成成功');
  } catch (error: any) {
    message.error(error.response?.data?.message || '鎽樿鐢熸垚澶辫触锛岃妫€鏌ョ綉缁滄垨閰嶇疆');
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
      // 鍚堝苟鐜版湁鏍囩鍜屾帹鑽愭爣绛撅紝鍘婚噸
      const newTags = Array.from(new Set([...selectedTags.value, ...tags]));
      selectedTags.value = newTags;
      // 瑙﹀彂淇濆瓨
      await handleSave(true);
      message.success(`鎴愬姛鎺ㄨ崘骞舵坊鍔犱簡 ${tags.length} 涓爣绛撅紒`);
    } else {
      message.info('AI 鏈兘鎻愬彇鍑哄悎閫傜殑鏍囩');
    }
  } catch (error: any) {
    message.error(error.response?.data?.message || '鏍囩鎺ㄨ崘澶辫触');
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
    title: '纭鍥炴粴',
    content: '回滚后当前内容将被覆盖，同时会生成一条新的历史记录，是否继续？',
    onOk: async () => {
      const res = await noteStore.rollbackToHistory(noteStore.currentNote!.id, previewHistory.value.id);
      if (res) {
        message.success('鍥炴粴鎴愬姛');
        previewModalVisible.value = false;
        historyDrawerVisible.value = false;
        
        // 寮哄埗閲嶆柊娓叉煋 MarkdownEditor 缁勪欢锛岃鍥炴粴鍚庣殑鍐呭绔嬪埢鏄剧ず
        editorKey.value += 1;
      }
    }
  });
};

// 绉诲姩涓庡鍒剁瑪璁扮浉鍏崇姸鎬?
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
      message.success('绗旇绉诲姩鎴愬姛');
      // If no notes left or current is gone, select first
      if (!noteStore.currentNote && noteStore.notes.length > 0 && noteStore.notes[0]) {
          handleSelectNote(noteStore.notes[0].id);
      }
    } else {
      await noteStore.copyNote(noteStore.currentNote.id, targetNotebookId.value);
      message.success('绗旇澶嶅埗鎴愬姛');
    }
    moveCopyModalVisible.value = false;
  } catch (error: any) {
    message.error(error.response?.data?.message || '鎿嶄綔澶辫触');
  }
};

const handleDeleteNote = () => {
  if (!noteStore.currentNote) return;
  Modal.confirm({
    title: '移至回收站',
    content: '绗旇灏嗙Щ鑷冲洖鏀剁珯锛屽彲浠ュ湪鍥炴敹绔欎腑鎭㈠鎴栧交搴曞垹闄わ紝纭鍒犻櫎鍚楋紵',
    okText: '移至回收站',
    okType: 'danger',
    cancelText: '鍙栨秷',
    onOk: async () => {
      try {
        await noteStore.deleteNote(noteStore.currentNote!.id);
        message.success('绗旇宸茬Щ鑷冲洖鏀剁珯');
        // Auto select another note if available
        if (noteStore.notes.length > 0 && noteStore.notes[0]) {
          handleSelectNote(noteStore.notes[0].id);
        }
      } catch (error: any) {
        message.error('鍒犻櫎澶辫触');
      }
    }
  });
};
// 鍒嗕韩绗旇鐩稿叧鐘舵€?
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
    message.success('鍒嗕韩閾炬帴鐢熸垚鎴愬姛');
  } catch (error: any) {
    message.error('鐢熸垚澶辫触');
  }
};

const handleDisableShare = async () => {
  if (!noteStore.currentNote) return;
  try {
    await noteStore.disableShare(noteStore.currentNote.id);
    shareInfo.value = null;
    message.success('分享已关闭');
  } catch (error: any) {
    message.error('鍏抽棴澶辫触');
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
  
  // 鎻愮ず鐢ㄦ埛姝ｅ湪瀵煎嚭
  const hide = message.loading('姝ｅ湪瀵煎嚭...', 0);
  try {
    const success = await noteStore.exportNoteToMarkdown(
      noteStore.currentNote.id, 
      noteStore.currentNote.title || '未命名笔记'
    );
    if (success) {
      message.success('瀵煎嚭鎴愬姛');
    } else {
      message.error('瀵煎嚭澶辫触锛岃閲嶈瘯');
    }
  } finally {
    hide();
  }
};
const handleExportPdf = async () => {
  if (!noteStore.currentNote) return;

  const hide = message.loading('姝ｅ湪瀵煎嚭...', 0);
  try {
    const success = await noteStore.exportNoteToPdf(
      noteStore.currentNote.id,
      noteStore.currentNote.title || '未命名笔记'
    );
    if (success) {
      message.success('PDF 瀵煎嚭鎴愬姛');
    } else {
      message.error('PDF 瀵煎嚭澶辫触锛岃閲嶈瘯');
    }
  } finally {
    hide();
  }
};

const handleExportWord = async () => {
  if (!noteStore.currentNote) return;

  const hide = message.loading('姝ｅ湪瀵煎嚭...', 0);
  try {
    const success = await noteStore.exportNoteToWord(
      noteStore.currentNote.id,
      noteStore.currentNote.title || '未命名笔记'
    );
    if (success) {
      message.success('Word 瀵煎嚭鎴愬姛');
    } else {
      message.error('Word 瀵煎嚭澶辫触锛岃閲嶈瘯');
    }
  } finally {
    hide();
  }
};

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
                  <span class="note-title">{{ item.title || '未命名笔记' }}</span>
                </template>
              </a-list-item-meta>
            </a-list-item>
          </template>
        </a-list>
      </div>
    </div>

    <div v-if="noteStore.currentNote" class="editor-area">
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
          <a-button type="default" @click="openAIAssistant" style="margin-right: 8px;">
            <template #icon><RobotOutlined /></template>
            AI 问答
          </a-button>
          <a-button type="default" @click="handleOpenHistory" style="margin-right: 8px;">
            <template #icon><HistoryOutlined /></template>
            历史
          </a-button>
          <a-button type="default" @click="handleOpenShareModal" style="margin-right: 8px;">
            <template #icon><ShareAltOutlined /></template>
            分享
          </a-button>
          <a-button type="default" @click="openCommentArea" style="margin-right: 8px;">
            <template #icon><MessageOutlined /></template>
            评论区
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
            <template #suffixIcon><TagOutlined /></template>
          </a-select>
          <a-button type="dashed" @click="handleSuggestTags" :loading="suggestingTags" title="智能推荐标签">
            <template #icon><RobotOutlined /></template>
            智能推荐
          </a-button>
        </div>

        <div v-if="noteStore.currentNote.summary" class="summary-area" style="margin: 12px 12px 0 12px;">
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

/* 淇濊瘉 Markdown 鍐呭鐨勫浘鐗囩瓑涓嶄細瓒呭嚭杈圭晫 */
.markdown-body img {
  max-width: 100%;
}
</style>

