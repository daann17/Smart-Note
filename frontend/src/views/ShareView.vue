<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message, Modal } from 'ant-design-vue';
import { LockOutlined, LoginOutlined, MessageOutlined, PushpinOutlined, SaveOutlined } from '@ant-design/icons-vue';
import api from '../api';
import MarkdownEditor from '../components/MarkdownEditor.vue';
import { decorateShareContent, type ShareAnchor } from '../utils/shareAnchors';
import type { ShareComment } from '../stores/note';
import { collectShareAnchorCommentCounts, countShareCommentMessages } from '../utils/shareComments';
import { getOrCreateShareCommentOwnerToken } from '../utils/shareCommentOwnership';

type ShareNote = {
  noteId: number;
  title: string;
  content: string;
  contentHtml: string;
  summary: string | null;
  updatedAt: string;
  author: string;
  allowComment: boolean;
  allowEdit: boolean;
  shareId: number;
};

const route = useRoute();
const router = useRouter();
const token = route.params.token as string;
const commentOwnerToken = getOrCreateShareCommentOwnerToken(token);
const shareEditorUser =
  localStorage.getItem('displayName') || localStorage.getItem('username') || '\u534f\u4f5c\u8005';

const loading = ref(true);
const note = ref<ShareNote | null>(null);
const error = ref('');
const requireCode = ref(false);
const extractionCode = ref('');
const submittingCode = ref(false);

const comments = ref<ShareComment[]>([]);
const newCommentContent = ref('');
const newCommentAuthor = ref('');
const submittingComment = ref(false);
const activeAnchor = ref<ShareAnchor | null>(null);
const previewContentRef = ref<HTMLElement | null>(null);
const readonlyContentRef = ref<HTMLElement | null>(null);
const activeReplyCommentId = ref<number | null>(null);
const replySubmittingId = ref<number | null>(null);
const deleteCommentId = ref<number | null>(null);
const replyContentDrafts = ref<Record<number, string>>({});
const replyAuthorDrafts = ref<Record<number, string>>({});

const lastSavedTime = ref('');
const isSaving = ref(false);
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

const isAuthenticated = computed(() => Boolean(localStorage.getItem('token')));
const canCollaborate = computed(() => Boolean(note.value?.allowEdit && isAuthenticated.value));
const needsLoginForCollab = computed(() => Boolean(note.value?.allowEdit && !isAuthenticated.value));

const commentCountMap = computed(() => collectShareAnchorCommentCounts(comments.value));
const commentMessageCount = computed(() => countShareCommentMessages(comments.value));

const decoratedDocument = computed(() =>
  decorateShareContent({
    contentHtml: note.value?.contentHtml,
    activeAnchorKey: activeAnchor.value?.key || null,
    commentCountByKey: commentCountMap.value,
    anchorIdPrefix: 'share-anchor',
  }),
);

const decoratedContentHtml = computed(() => decoratedDocument.value.html);
const availableAnchors = computed(() => decoratedDocument.value.anchors);

const commentAnchorState = computed(() => {
  const states = new Map<string, { present: boolean; anchor?: ShareAnchor }>();

  for (const comment of comments.value) {
    if (!comment.anchorKey) {
      continue;
    }

    const matchedAnchor = availableAnchors.value.find((anchor) => anchor.key === comment.anchorKey);
    states.set(comment.anchorKey, {
      present: Boolean(matchedAnchor),
      anchor: matchedAnchor,
    });
  }

  return states;
});

const commentInputTitle = computed(() => (
  activeAnchor.value
    ? '\u53d1\u8868\u8bc4\u8bba\u5230\u5f53\u524d\u6bb5\u843d'
    : '\u53d1\u8868\u8bc4\u8bba'
));

const activeAnchorSummary = computed(() => activeAnchor.value?.label || '');
const activeAnchorPreviewText = computed(() => activeAnchor.value?.preview || '');

const canDeleteComment = (comment: ShareComment) => {
  if (!comment.viewerCanDelete) {
    return false;
  }

  if (comment.parentCommentId !== null) {
    return true;
  }

  return comment.replies.every((reply) => reply.viewerCanDelete);
};

const getInteractiveAnchorRoots = () => (
  [previewContentRef.value, readonlyContentRef.value].filter(Boolean) as HTMLElement[]
);

const syncActiveAnchorHighlight = (anchorKey: string | null) => {
  for (const root of getInteractiveAnchorRoots()) {
    const anchorBlocks = root.querySelectorAll<HTMLElement>('[data-share-anchor-key]');

    anchorBlocks.forEach((element) => {
      element.classList.toggle('is-active', Boolean(anchorKey) && element.dataset.shareAnchorKey === anchorKey);
    });
  }
};

const clearActiveAnchor = () => {
  activeAnchor.value = null;
  syncActiveAnchorHighlight(null);
};

const handleGoLogin = () => {
  router.push({
    path: '/',
    query: {
      redirect: route.fullPath,
    },
  });
};

const scrollToAnchor = async (anchorKey: string) => {
  await nextTick();
  const target = document.getElementById(`share-anchor-${anchorKey}`);
  if (!target) {
    message.warning('\u539f\u6bb5\u843d\u5df2\u53d8\u66f4\uff0c\u6682\u65f6\u65e0\u6cd5\u5b9a\u4f4d');
    return;
  }

  activeAnchor.value = availableAnchors.value.find((anchor) => anchor.key === anchorKey) || null;
  syncActiveAnchorHighlight(anchorKey);
  target.scrollIntoView({ behavior: 'smooth', block: 'center' });
};

const handleAnchorClick = (event: MouseEvent) => {
  if (!note.value?.allowComment) {
    return;
  }

  const target = event.target as HTMLElement | null;
  if (target?.closest('a')) {
    return;
  }

  const anchorElement = target?.closest<HTMLElement>('[data-share-anchor-key]');
  const anchorKey = anchorElement?.dataset.shareAnchorKey;
  if (!anchorKey) {
    return;
  }

  if (activeAnchor.value?.key === anchorKey) {
    activeAnchor.value = null;
    syncActiveAnchorHighlight(null);
    return;
  }

  syncActiveAnchorHighlight(anchorKey);
  activeAnchor.value = availableAnchors.value.find((anchor) => anchor.key === anchorKey) || null;
};

const handleSave = async (isAutoSave = false) => {
  if (!note.value || !canCollaborate.value) {
    return;
  }

  isSaving.value = true;
  try {
    const payload: Record<string, unknown> = {
      content: note.value.content,
      contentHtml: note.value.contentHtml,
    };

    if (extractionCode.value) {
      payload.code = extractionCode.value;
    }

    const response = await api.put(`/public/shares/${token}`, payload);
    note.value.updatedAt = response.data.updatedAt;

    const now = new Date();
    lastSavedTime.value = `${now.getHours().toString().padStart(2, '0')}:${now
      .getMinutes()
      .toString()
      .padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`;

    if (!isAutoSave) {
      message.success('\u4fdd\u5b58\u6210\u529f');
    }
  } catch (err: any) {
    if (!isAutoSave) {
      message.error(err.response?.data?.message || '\u4fdd\u5b58\u5931\u8d25');
    }
  } finally {
    isSaving.value = false;
  }
};

const handleKeyDown = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key === 's' && canCollaborate.value) {
    event.preventDefault();
    void handleSave(false);
  }
};

watch(
  () => note.value?.content,
  (newValue, oldValue) => {
    if (!canCollaborate.value || oldValue === undefined || newValue === oldValue) {
      return;
    }

    if (autoSaveTimer) {
      clearTimeout(autoSaveTimer);
    }

    autoSaveTimer = setTimeout(() => {
      void handleSave(true);
    }, 3000);
  },
);

watch(
  availableAnchors,
  (anchors) => {
    if (!activeAnchor.value) {
      return;
    }

    activeAnchor.value = anchors.find((anchor) => anchor.key === activeAnchor.value?.key) || null;
  },
  { immediate: true },
);

watch(
  () => activeAnchor.value?.key || null,
  async (anchorKey) => {
    await nextTick();
    syncActiveAnchorHighlight(anchorKey);
  },
  { immediate: true },
);

const fetchComments = async () => {
  try {
    const response = await api.get<ShareComment[]>(`/public/shares/${token}/comments`, {
      params: {
        ...(extractionCode.value ? { code: extractionCode.value } : {}),
        ownerToken: commentOwnerToken,
      },
    });
    comments.value = response.data;
  } catch (err: any) {
    console.error('Failed to fetch comments', err);
    if (err.response?.status === 403) {
      message.error(err.response?.data?.message || '\u65e0\u6cd5\u52a0\u8f7d\u8bc4\u8bba');
    }
  }
};

const submitComment = async () => {
  if (!newCommentContent.value.trim()) {
    message.warning('\u8bc4\u8bba\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a');
    return;
  }

  submittingComment.value = true;
  try {
    await api.post(`/public/shares/${token}/comments`, {
      content: newCommentContent.value.trim(),
      authorName: newCommentAuthor.value.trim() || '\u533f\u540d\u7528\u6237',
      code: extractionCode.value || undefined,
      ownerToken: commentOwnerToken,
      anchorKey: activeAnchor.value?.key || undefined,
      anchorType: activeAnchor.value?.type || undefined,
      anchorLabel: activeAnchor.value?.label || undefined,
      anchorPreview: activeAnchor.value?.preview || undefined,
    });

    message.success(
      activeAnchor.value ? '\u6bb5\u843d\u8bc4\u8bba\u5df2\u53d1\u5e03' : '\u8bc4\u8bba\u6210\u529f',
    );
    newCommentContent.value = '';
    await fetchComments();
  } catch (err: any) {
    message.error(err.response?.data?.message || '\u8bc4\u8bba\u5931\u8d25');
  } finally {
    submittingComment.value = false;
  }
};

const toggleReplyEditor = (comment: ShareComment) => {
  if (activeReplyCommentId.value === comment.id) {
    activeReplyCommentId.value = null;
    return;
  }

  activeReplyCommentId.value = comment.id;
  replyContentDrafts.value[comment.id] ||= '';
  replyAuthorDrafts.value[comment.id] ||= newCommentAuthor.value.trim();
};

const resetReplyDraft = (commentId: number) => {
  activeReplyCommentId.value = activeReplyCommentId.value === commentId ? null : activeReplyCommentId.value;
  delete replyContentDrafts.value[commentId];
  delete replyAuthorDrafts.value[commentId];
};

const submitReply = async (comment: ShareComment) => {
  const content = replyContentDrafts.value[comment.id]?.trim();
  if (!content) {
    message.warning('回复内容不能为空');
    return;
  }

  replySubmittingId.value = comment.id;
  try {
    await api.post(`/public/shares/${token}/comments`, {
      content,
      authorName: replyAuthorDrafts.value[comment.id]?.trim() || '\u533f\u540d\u7528\u6237',
      code: extractionCode.value || undefined,
      ownerToken: commentOwnerToken,
      parentCommentId: comment.id,
    });

    message.success('回复已发送');
    resetReplyDraft(comment.id);
    await fetchComments();
  } catch (err: any) {
    message.error(err.response?.data?.message || '\u56de\u590d\u5931\u8d25');
  } finally {
    replySubmittingId.value = null;
  }
};

const handleDeleteComment = (comment: ShareComment) => {
  if (deleteCommentId.value === comment.id) {
    return;
  }

  const isReply = comment.parentCommentId !== null;
  const hasForeignReplies = comment.parentCommentId === null && comment.replies.some((reply) => !reply.viewerCanDelete);

  if (hasForeignReplies) {
    Modal.info({
      title: '删除这条评论？',
      content: '这条评论已有他人的回复，当前不能直接删除。',
      okText: '知道了',
    });
    return;
  }

  Modal.confirm({
    title: isReply ? '删除这条回复？' : '删除这条评论？',
    content: '删除后无法恢复。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      deleteCommentId.value = comment.id;
      try {
        await api.delete(`/public/shares/${token}/comments/${comment.id}`, {
          params: {
            ...(extractionCode.value ? { code: extractionCode.value } : {}),
            ownerToken: commentOwnerToken,
          },
        });

        if (activeReplyCommentId.value === comment.id) {
          resetReplyDraft(comment.id);
        }

        await fetchComments();
        message.success(isReply ? '回复已删除' : '评论已删除');
      } catch (err: any) {
        message.error(err.response?.data?.message || '删除失败');
      } finally {
        deleteCommentId.value = null;
      }
    },
  });
};

const fetchNoteContent = async (code?: string) => {
  try {
    if (code) {
      submittingCode.value = true;
      extractionCode.value = code.trim();
    }

    const response = await api.post<ShareNote>(`/public/shares/${token}`, {
      code: extractionCode.value || undefined,
    });
    note.value = response.data;
    requireCode.value = false;
    activeAnchor.value = null;

    if (note.value.title) {
      document.title = `${note.value.title} - SmartNote`;
    }

    if (note.value.allowComment) {
      await fetchComments();
    }
  } catch (err: any) {
    if (err.response?.status === 403) {
      message.error(err.response?.data?.message || '\u63d0\u53d6\u7801\u9519\u8bef');
    } else {
      error.value =
        err.response?.data?.message ||
        '\u65e0\u6cd5\u83b7\u53d6\u5206\u4eab\u5185\u5bb9\uff0c\u94fe\u63a5\u53ef\u80fd\u5df2\u5931\u6548';
      message.error(error.value);
    }
  } finally {
    loading.value = false;
    if (code) {
      submittingCode.value = false;
    }
  }
};

const checkShareInfo = async () => {
  try {
    const response = await api.get(`/public/shares/${token}/info`);
    if (response.data.requireCode) {
      requireCode.value = true;
      loading.value = false;
    } else {
      await fetchNoteContent();
    }
  } catch (err: any) {
    error.value =
      err.response?.data?.message ||
      '\u65e0\u6cd5\u83b7\u53d6\u5206\u4eab\u5185\u5bb9\uff0c\u94fe\u63a5\u53ef\u80fd\u5df2\u5931\u6548';
    message.error(error.value);
    loading.value = false;
  }
};

onMounted(() => {
  document.addEventListener('keydown', handleKeyDown);
  void checkShareInfo();
});

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeyDown);
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer);
  }
});
</script>

<template>
    <div class="share-container">
    <div v-if="loading" class="loading-state">
      <a-spin size="large" tip="正在加载分享内容..." />
    </div>

    <div v-else-if="error" class="error-state">
      <a-result status="error" title="访问失败" :sub-title="error">
        <template #extra>
          <a-button type="primary" @click="router.push('/')">返回首页</a-button>
        </template>
      </a-result>
    </div>

    <div v-else-if="requireCode" class="code-state">
      <a-card title="需要提取码" class="code-card">
        <div class="code-hint">
          <LockOutlined class="lock-icon" />
          <p>这是受保护的分享笔记，请先输入提取码</p>
        </div>

        <a-input-search
          v-model:value="extractionCode"
          placeholder="请输入提取码"
          enter-button="确认"
          size="large"
          :loading="submittingCode"
          @search="fetchNoteContent(extractionCode)"
        />
      </a-card>
    </div>

    <div v-else-if="note" class="share-content-wrapper">
      <div class="share-header">
        <h1 class="title">{{ note.title }}</h1>

        <div class="meta-row">
          <div class="meta">
            <span>作者：{{ note.author }}</span>
            <span class="meta-divider">|</span>
            <span>最后更新：{{ new Date(note.updatedAt).toLocaleString() }}</span>
          </div>

          <div v-if="canCollaborate" class="action-bar">
            <span v-if="lastSavedTime" class="save-tip">自动保存于 {{ lastSavedTime }}</span>
            <a-button type="primary" size="small" :loading="isSaving" @click="handleSave(false)">
              <template #icon><SaveOutlined /></template>
              保存
            </a-button>
          </div>

          <div v-else-if="needsLoginForCollab" class="action-bar">
            <a-button type="primary" size="small" @click="handleGoLogin">
              <template #icon><LoginOutlined /></template>
              登录后协同编辑
            </a-button>
          </div>
        </div>
      </div>

      <div v-if="note.summary" class="summary-area">
        <a-alert message="AI 智能摘要" :description="note.summary" type="info" show-icon />
      </div>

      <div v-if="canCollaborate" class="editor-area-wrapper">
        <div class="editor-tip">
          当前分享已开启协同编辑，登录用户可实时协作；下方预览区支持段落定位评论。
        </div>
        <div class="editor-container">
          <MarkdownEditor
            v-model="note.content"
            :noteId="note.noteId"
            :collab="true"
            :currentUser="shareEditorUser"
            :shareToken="token"
            @update:contentHtml="(html) => (note!.contentHtml = html)"
          />
        </div>

        <div v-if="note.allowComment" class="anchor-preview-card">
          <div class="anchor-preview-head">
            <div>
              <h3>段落评论定位预览</h3>
              <p>点击标题、段落或列表项，可以把评论挂到具体位置。</p>
            </div>
            <a-tag color="blue">{{ availableAnchors.length }} 个可评论段落</a-tag>
          </div>
          <div
            ref="previewContentRef"
            class="markdown-body content-area interactive-content anchor-preview"
            v-html="decoratedContentHtml"
            @click="handleAnchorClick"
          ></div>
        </div>
      </div>

      <div v-else class="content-readonly">
        <a-alert
          v-if="needsLoginForCollab"
          class="login-alert"
          message="协同编辑需要登录"
          description="当前分享链接允许协作。为保护文档内容，登录后才能进入实时协同编辑。"
          type="warning"
          show-icon
        >
          <template #action>
            <a-button size="small" type="primary" @click="handleGoLogin">立即登录</a-button>
          </template>
        </a-alert>

        <div
          ref="readonlyContentRef"
          class="markdown-body content-area interactive-content"
          v-html="decoratedContentHtml"
          @click="handleAnchorClick"
        ></div>
      </div>

      <div v-if="note.allowComment && activeAnchor" class="anchor-selection-banner">
        <div class="anchor-selection-main">
          <PushpinOutlined class="anchor-selection-icon" />
          <div class="anchor-selection-copy">
            <strong>已选中评论位置</strong>
            <span>{{ activeAnchorSummary }}</span>
            <small>{{ activeAnchorPreviewText }}</small>
          </div>
        </div>
        <button type="button" class="anchor-selection-clear" @click="clearActiveAnchor">
          改为全文评论
        </button>
      </div>

      <div v-if="note.allowComment" class="comments-section">
        <div class="comments-head">
          <div>
            <h3 class="comments-title">评论协作</h3>
            <p class="comments-subtitle">支持全文评论，也支持挂到具体段落、标题或列表项上。</p>
          </div>
          <a-tag color="processing">{{ commentMessageCount }} 条留言</a-tag>
        </div>

        <div class="comment-input-area">
          <div class="comment-mode-row">
            <div class="comment-mode-title">
              <MessageOutlined />
              <span>{{ commentInputTitle }}</span>
            </div>

            <div v-if="activeAnchor" class="active-anchor-chip">
              <PushpinOutlined />
              <span>{{ activeAnchorSummary }}</span>
              <button type="button" class="clear-anchor-btn" @click="clearActiveAnchor">改为全文评论</button>
            </div>
            <span v-else class="comment-mode-hint">未选中段落时，默认发表评论到整篇笔记。</span>
          </div>

          <a-input
            v-model:value="newCommentAuthor"
            placeholder="你的称呼（可选）"
            class="author-input"
          />
          <a-textarea
            v-model:value="newCommentContent"
            placeholder="写下你的评论、建议或协作说明..."
            :rows="4"
          />
          <div class="comment-actions">
            <span class="comment-tip">点击文档中的段落后再发表评论，可以形成段落级评论。</span>
            <a-button type="primary" :loading="submittingComment" @click="submitComment">发表评论</a-button>
          </div>
        </div>

        <div class="comments-list">
          <a-empty v-if="comments.length === 0" description="暂无评论，来留下第一条吧" />
          <div v-for="comment in comments" :key="comment.id" class="comment-item" :class="{ resolved: comment.resolved }">
            <div class="comment-header">
              <div class="comment-header-main">
                <span class="comment-author">{{ comment.authorName }}</span>
                <a-tag v-if="comment.authorComment" color="gold">作者</a-tag>
                <span class="comment-time">{{ new Date(comment.createdAt).toLocaleString() }}</span>
              </div>

              <button
                v-if="comment.anchorKey"
                type="button"
                class="comment-anchor-btn"
                :class="{ missing: !commentAnchorState.get(comment.anchorKey)?.present }"
                @click="scrollToAnchor(comment.anchorKey)"
              >
                {{ comment.anchorLabel || '段落评论' }}
              </button>
              <a-tag v-else color="default">全文评论</a-tag>
            </div>

            <div class="comment-status-row">
              <a-tag :color="comment.resolved ? 'success' : 'processing'">
                {{ comment.resolved ? '已解决' : '待处理' }}
              </a-tag>
              <span v-if="comment.resolved && comment.resolvedAt" class="comment-resolved-meta">
                {{ comment.resolvedBy || '作者' }} 于 {{ new Date(comment.resolvedAt).toLocaleString() }} 标记
              </span>
            </div>

            <div v-if="comment.anchorPreview" class="comment-anchor-preview">
              {{ comment.anchorPreview }}
            </div>
            <div class="comment-content">{{ comment.content }}</div>

            <div class="comment-thread-actions">
              <button
                type="button"
                class="comment-reply-btn"
                :class="{ active: activeReplyCommentId === comment.id }"
                :disabled="deleteCommentId === comment.id"
                @click="toggleReplyEditor(comment)"
              >
                {{ activeReplyCommentId === comment.id ? '取消回复' : '回复这条评论' }}
              </button>
              <button
                v-if="comment.viewerCanDelete"
                type="button"
                class="comment-delete-btn"
                :class="{ blocked: !canDeleteComment(comment) }"
                :disabled="deleteCommentId === comment.id"
                :title="canDeleteComment(comment) ? '删除这条评论' : '这条评论下已有他人的回复，暂时不能直接删除'"
                @click="handleDeleteComment(comment)"
              >
                {{ deleteCommentId === comment.id ? '删除中...' : '删除' }}
              </button>
              <span v-if="comment.replies.length > 0" class="comment-reply-count">
                {{ comment.replies.length }} 条回复
              </span>
            </div>

            <div v-if="comment.replies.length > 0" class="comment-replies">
              <div v-for="reply in comment.replies" :key="reply.id" class="comment-reply-item">
                <div class="comment-header">
                  <div class="comment-header-main">
                    <span class="comment-author">{{ reply.authorName }}</span>
                    <a-tag v-if="reply.authorComment" color="gold">作者</a-tag>
                    <span class="comment-time">{{ new Date(reply.createdAt).toLocaleString() }}</span>
                  </div>
                  <button
                    v-if="reply.viewerCanDelete"
                    type="button"
                    class="comment-delete-btn"
                    :disabled="deleteCommentId === reply.id"
                    @click="handleDeleteComment(reply)"
                  >
                    {{ deleteCommentId === reply.id ? '删除中...' : '删除' }}
                  </button>
                </div>
                <div class="comment-content">{{ reply.content }}</div>
              </div>
            </div>

            <div v-if="activeReplyCommentId === comment.id" class="comment-reply-editor">
              <a-input
                v-model:value="replyAuthorDrafts[comment.id]"
                placeholder="你的称呼（可选）"
                class="author-input"
              />
              <a-textarea
                v-model:value="replyContentDrafts[comment.id]"
                placeholder="补充说明或直接回复这条评论..."
                :rows="3"
              />
              <div class="comment-actions reply-actions">
                <span class="comment-tip">回复会继续挂在当前评论对应的位置下。</span>
                <div class="reply-action-buttons">
                  <a-button @click="resetReplyDraft(comment.id)">取消</a-button>
                  <a-button
                    type="primary"
                    :loading="replySubmittingId === comment.id"
                    @click="submitReply(comment)"
                  >
                    发送回复
                  </a-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="share-footer">
        由 <a href="/" target="_blank" rel="noreferrer">SmartNote</a> 提供支持
      </div>
    </div>
  </div>
</template>

<style scoped>
.share-container {
  min-height: 100vh;
  padding: 40px 20px;
  display: flex;
  justify-content: center;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.1), transparent 24%),
    radial-gradient(circle at bottom right, rgba(15, 118, 110, 0.12), transparent 26%),
    linear-gradient(180deg, #f8fbff 0%, #f3f6fb 100%);
}

.loading-state,
.error-state,
.code-state {
  width: 100%;
  display: flex;
  justify-content: center;
  margin-top: 100px;
}

.code-card {
  width: min(420px, 100%);
  margin-top: 72px;
  border-radius: 20px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.code-hint {
  margin-bottom: 24px;
  color: #475569;
  text-align: center;
}

.lock-icon {
  margin-bottom: 16px;
  font-size: 48px;
  color: #2563eb;
}

.share-content-wrapper {
  width: 100%;
  max-width: 980px;
  min-height: 80vh;
  padding: 40px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 22px 52px rgba(15, 23, 42, 0.08);
  border: 1px solid rgba(148, 163, 184, 0.16);
  display: flex;
  flex-direction: column;
}

.share-header {
  padding-bottom: 22px;
  margin-bottom: 20px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.88);
}

.title {
  margin: 0 0 16px;
  font-size: 34px;
  color: #0f172a;
  line-height: 1.2;
}

.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.meta {
  color: #64748b;
  font-size: 14px;
}

.meta-divider {
  margin: 0 8px;
}

.action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.save-tip {
  color: #64748b;
  font-size: 12px;
}

.summary-area {
  margin-bottom: 24px;
}

.editor-area-wrapper,
.content-readonly {
  display: flex;
  flex-direction: column;
  gap: 18px;
  flex: 1;
}

.editor-tip {
  color: #2563eb;
  font-weight: 600;
}

.editor-container {
  min-height: 460px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 22px;
  overflow: hidden;
}

.anchor-preview-card {
  padding: 20px;
  border-radius: 22px;
  background: rgba(248, 250, 252, 0.86);
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.anchor-preview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.anchor-preview-head h3 {
  margin: 0;
  color: #0f172a;
  font-size: 18px;
}

.anchor-preview-head p {
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.login-alert {
  margin-bottom: 8px;
}

.content-area {
  color: #1f2937;
  font-size: 16px;
  line-height: 1.85;
}

.anchor-preview {
  max-height: 460px;
  overflow: auto;
  padding-right: 8px;
}

.interactive-content {
  cursor: default;
}

.interactive-content :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}

.interactive-content :deep(.share-anchor-block) {
  position: relative;
  margin: 0 -10px;
  padding: 6px 10px;
  border-radius: 12px;
  transition: background-color 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
  border: 1px solid transparent;
  cursor: pointer;
}

.interactive-content :deep(.share-anchor-block:hover) {
  background: rgba(239, 246, 255, 0.78);
  border-color: rgba(37, 99, 235, 0.14);
}

.interactive-content :deep(.share-anchor-block.is-active) {
  background: linear-gradient(90deg, rgba(219, 234, 254, 0.96) 0%, rgba(239, 246, 255, 0.92) 100%);
  border-color: rgba(37, 99, 235, 0.34);
  box-shadow:
    inset 4px 0 0 #2563eb,
    0 12px 26px rgba(37, 99, 235, 0.12);
}

.interactive-content :deep(.share-anchor-block.is-active)::before {
  position: absolute;
  left: 12px;
  top: -11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: #2563eb;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  box-shadow: 0 10px 18px rgba(37, 99, 235, 0.18);
}

.anchor-selection-banner {
  margin-top: 20px;
  padding: 16px 18px;
  border-radius: 18px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.96) 0%, rgba(248, 250, 252, 0.92) 100%);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.anchor-selection-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.anchor-selection-icon {
  margin-top: 2px;
  color: #2563eb;
  font-size: 18px;
}

.anchor-selection-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.anchor-selection-copy strong {
  color: #0f172a;
  font-size: 14px;
}

.anchor-selection-copy span {
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 700;
}

.anchor-selection-copy small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.anchor-selection-clear {
  border: none;
  background: #2563eb;
  color: #fff;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.interactive-content :deep(.share-anchor-block.has-comments)::after {
  content: attr(data-comment-count-label);
  position: absolute;
  top: -10px;
  right: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: #0f766e;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  box-shadow: 0 10px 16px rgba(15, 118, 110, 0.2);
}

.comments-section {
  margin-top: 40px;
  padding-top: 24px;
  border-top: 1px solid rgba(226, 232, 240, 0.88);
}

.comments-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.comments-title {
  margin: 0;
  color: #0f172a;
  font-size: 24px;
}

.comments-subtitle {
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.comment-input-area {
  padding: 18px;
  border-radius: 20px;
  background: rgba(248, 250, 252, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.14);
}

.comment-mode-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.comment-mode-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #0f172a;
  font-weight: 700;
}

.comment-mode-hint {
  color: #64748b;
  font-size: 13px;
}

.active-anchor-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(219, 234, 254, 0.86);
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
}

.clear-anchor-btn {
  border: none;
  background: transparent;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.author-input {
  width: min(240px, 100%);
  margin-bottom: 12px;
}

.comment-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.comment-tip {
  color: #64748b;
  font-size: 12px;
}

.comments-list {
  margin-top: 20px;
}

.comment-item {
  padding: 18px 0;
  border-bottom: 1px solid rgba(226, 232, 240, 0.72);
}

.comment-item.resolved {
  opacity: 0.92;
}

.comment-item:last-child {
  border-bottom: none;
}

.comment-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.comment-header-main {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.comment-author {
  color: #0f172a;
  font-weight: 700;
}

.comment-time {
  color: #94a3b8;
  font-size: 12px;
}

.comment-anchor-btn {
  border: 1px solid rgba(37, 99, 235, 0.2);
  background: rgba(239, 246, 255, 0.86);
  color: #1d4ed8;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.comment-anchor-btn.missing {
  color: #b45309;
  border-color: rgba(217, 119, 6, 0.22);
  background: rgba(255, 247, 237, 0.9);
}

.comment-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.comment-resolved-meta {
  color: #64748b;
  font-size: 12px;
}

.comment-anchor-preview {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.88);
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
}

.comment-content {
  color: #1f2937;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-thread-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.comment-reply-btn {
  border: none;
  background: transparent;
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  padding: 0;
}

.comment-reply-btn:disabled {
  color: #94a3b8;
  cursor: wait;
}

.comment-reply-btn.active {
  color: #1d4ed8;
}

.comment-delete-btn {
  border: none;
  background: transparent;
  color: #dc2626;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  padding: 0;
}

.comment-delete-btn.blocked {
  color: #c2410c;
}

.comment-delete-btn:disabled {
  color: #fca5a5;
  cursor: wait;
}

.comment-reply-count {
  color: #64748b;
  font-size: 12px;
}

.comment-replies {
  margin-top: 16px;
  padding-left: 16px;
  border-left: 2px solid rgba(191, 219, 254, 0.9);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.comment-reply-item {
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.92);
  border: 1px solid rgba(226, 232, 240, 0.88);
}

.comment-reply-editor {
  margin-top: 16px;
  padding: 16px;
  border-radius: 18px;
  background: rgba(239, 246, 255, 0.62);
  border: 1px solid rgba(147, 197, 253, 0.3);
}

.reply-actions {
  align-items: center;
}

.reply-action-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.share-footer {
  margin-top: 40px;
  padding-top: 20px;
  border-top: 1px solid rgba(226, 232, 240, 0.88);
  text-align: center;
  color: #94a3b8;
  font-size: 14px;
}

.share-footer a {
  color: #2563eb;
  text-decoration: none;
}

@media (max-width: 900px) {
  .share-container {
    padding: 16px;
  }

  .share-content-wrapper {
    padding: 20px;
    border-radius: 20px;
  }

  .title {
    font-size: 28px;
  }

  .anchor-preview-head,
  .anchor-selection-banner,
  .comments-head,
  .comment-actions {
    flex-direction: column;
    align-items: stretch;
  }
}

/* Notion-inspired overrides */
.share-container {
  align-items: flex-start;
  padding: 32px 20px 56px;
  background: transparent;
}

.loading-state,
.error-state,
.code-state {
  max-width: 1040px;
  margin: 56px auto 0;
}

.code-card {
  border-radius: 20px;
  border: 1px solid var(--sn-border);
  box-shadow: var(--sn-shadow-card);
  background: var(--sn-surface);
}

.code-hint {
  color: var(--sn-text-soft);
}

.lock-icon {
  color: var(--sn-focus);
}

.share-content-wrapper {
  max-width: 1040px;
  min-height: auto;
  padding: 40px;
  border-radius: 24px;
  background: var(--sn-surface);
  border: 1px solid var(--sn-border);
  box-shadow: var(--sn-shadow-deep);
}

.share-header {
  padding-bottom: 24px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--sn-border);
}

.title {
  max-width: 780px;
  margin-bottom: 18px;
  font-size: clamp(34px, 4vw, 56px);
  line-height: 1.04;
  letter-spacing: -1.5px;
  color: var(--sn-text);
}

.meta {
  color: var(--sn-text-soft);
}

.save-tip {
  color: var(--sn-text-muted);
}

.summary-area {
  margin-bottom: 28px;
}

.summary-area :deep(.ant-alert) {
  border-radius: 16px;
}

.editor-area-wrapper,
.content-readonly {
  gap: 20px;
}

.editor-tip {
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid rgba(0, 117, 222, 0.14);
  background: var(--sn-blue-soft);
  color: var(--sn-focus);
  font-weight: 600;
}

.editor-container {
  border: 1px solid var(--sn-border);
  border-radius: 20px;
  background: var(--sn-surface);
  box-shadow: var(--sn-shadow-card);
}

.anchor-preview-card {
  padding: 22px;
  border-radius: 20px;
  background: var(--sn-bg-soft);
  border: 1px solid var(--sn-border);
}

.anchor-preview-head h3 {
  font-size: 22px;
  line-height: 1.15;
  letter-spacing: -0.3px;
  color: var(--sn-text);
}

.anchor-preview-head p {
  color: var(--sn-text-soft);
}

.content-area {
  color: var(--sn-text);
}

.interactive-content :deep(.share-anchor-block) {
  border-radius: 14px;
}

.interactive-content :deep(.share-anchor-block:hover) {
  background: rgba(0, 117, 222, 0.05);
  border-color: rgba(0, 117, 222, 0.12);
}

.interactive-content :deep(.share-anchor-block.is-active) {
  background: linear-gradient(90deg, rgba(242, 249, 255, 0.96) 0%, rgba(255, 255, 255, 0.98) 100%);
  border-color: rgba(0, 117, 222, 0.2);
  box-shadow:
    inset 4px 0 0 var(--sn-blue),
    0 12px 24px rgba(0, 117, 222, 0.08);
}

.interactive-content :deep(.share-anchor-block.is-active)::before {
  background: var(--sn-blue);
  box-shadow: 0 10px 18px rgba(0, 117, 222, 0.16);
}

.interactive-content :deep(.share-anchor-block.has-comments)::after {
  background: var(--sn-teal);
  box-shadow: 0 10px 16px rgba(42, 157, 153, 0.18);
}

.anchor-selection-banner {
  margin-top: 24px;
  border: 1px solid rgba(0, 117, 222, 0.14);
  background: linear-gradient(180deg, #ffffff 0%, var(--sn-blue-soft) 100%);
}

.anchor-selection-icon {
  color: var(--sn-focus);
}

.anchor-selection-copy strong {
  color: var(--sn-text);
}

.anchor-selection-copy span {
  color: var(--sn-focus);
}

.anchor-selection-copy small {
  color: var(--sn-text-soft);
}

.anchor-selection-clear {
  background: var(--sn-blue);
}

.comments-section {
  margin-top: 36px;
  padding-top: 28px;
  border-top: 1px solid var(--sn-border);
}

.comments-title {
  font-size: 28px;
  line-height: 1.12;
  letter-spacing: -0.5px;
  color: var(--sn-text);
}

.comments-subtitle {
  color: var(--sn-text-soft);
}

.comment-input-area {
  padding: 20px;
  border-radius: 20px;
  background: var(--sn-bg-soft);
  border: 1px solid var(--sn-border);
}

.comment-mode-title {
  color: var(--sn-text);
}

.comment-mode-hint,
.comment-tip,
.comment-time,
.comment-resolved-meta {
  color: var(--sn-text-soft);
}

.active-anchor-chip {
  border-radius: var(--sn-radius-pill);
  border: 1px solid rgba(0, 117, 222, 0.14);
  background: #ffffff;
}

.clear-anchor-btn {
  color: var(--sn-focus);
}

.comment-item {
  padding: 16px 18px;
  border-radius: 18px;
  background: #ffffff;
  border: 1px solid var(--sn-border);
  box-shadow: var(--sn-shadow-card);
}

.comment-item.resolved {
  background: #fbfff9;
  border-color: rgba(26, 174, 57, 0.18);
}

.comment-author {
  color: var(--sn-text);
}

.comment-anchor-btn {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: var(--sn-radius-pill);
  background: var(--sn-blue-soft);
  color: var(--sn-focus);
}

.comment-anchor-btn.missing {
  background: rgba(221, 91, 0, 0.1);
  color: var(--sn-orange);
}

.comment-anchor-preview {
  border: 1px solid var(--sn-border);
  background: var(--sn-bg-soft);
  color: var(--sn-text-soft);
}

.comment-content {
  color: var(--sn-text);
}

.comment-thread-actions {
  gap: 10px;
  flex-wrap: wrap;
}

.comment-reply-btn,
.comment-delete-btn {
  border-radius: var(--sn-radius-pill);
  padding: 6px 12px;
  border: 1px solid var(--sn-border);
  background: #ffffff;
}

.comment-reply-btn {
  color: var(--sn-focus);
}

.comment-reply-btn.active {
  background: var(--sn-blue-soft);
}

.comment-delete-btn {
  color: var(--sn-red);
}

.comment-delete-btn.blocked {
  background: rgba(221, 91, 0, 0.08);
  color: var(--sn-orange);
}

.comment-reply-count {
  color: var(--sn-text-muted);
}

.comment-replies {
  padding-left: 14px;
  border-left: 2px solid rgba(0, 0, 0, 0.06);
}

.comment-reply-item,
.comment-reply-editor {
  border-radius: 16px;
  border: 1px solid var(--sn-border);
  background: var(--sn-bg-soft);
}

.share-footer {
  margin-top: 36px;
  padding-top: 22px;
  border-top: 1px solid var(--sn-border);
  color: var(--sn-text-muted);
}

.share-footer a {
  color: var(--sn-blue);
}

@media (max-width: 900px) {
  .share-container {
    padding: 16px;
  }

  .share-content-wrapper {
    padding: 24px 20px;
    border-radius: 20px;
  }

  .title {
    font-size: clamp(28px, 8vw, 40px);
  }
}
</style>
