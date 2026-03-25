<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { LockOutlined, LoginOutlined, MessageOutlined, PushpinOutlined, SaveOutlined } from '@ant-design/icons-vue';
import api from '../api';
import MarkdownEditor from '../components/MarkdownEditor.vue';

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

type ShareComment = {
  id: number;
  content: string;
  authorName: string;
  createdAt: string;
  anchorKey: string | null;
  anchorType: string | null;
  anchorLabel: string | null;
  anchorPreview: string | null;
};

type ShareAnchorType = 'heading' | 'paragraph' | 'list' | 'quote' | 'code';

type ShareAnchor = {
  key: string;
  type: ShareAnchorType;
  label: string;
  preview: string;
  commentCount: number;
};

const route = useRoute();
const router = useRouter();
const token = route.params.token as string;
const shareEditorUser = localStorage.getItem('displayName') || localStorage.getItem('username') || '协作者';
const anchorSelector = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre';

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

const lastSavedTime = ref('');
const isSaving = ref(false);
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

const isAuthenticated = computed(() => Boolean(localStorage.getItem('token')));
const canCollaborate = computed(() => Boolean(note.value?.allowEdit && isAuthenticated.value));
const needsLoginForCollab = computed(() => Boolean(note.value?.allowEdit && !isAuthenticated.value));

const hashString = (value: string) => {
  let hash = 0;

  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }

  return hash.toString(36);
};

const normalizeAnchorText = (value: string) => value.replace(/\s+/g, ' ').trim();

const getAnchorType = (tagName: string): ShareAnchorType => {
  if (/^h[1-6]$/i.test(tagName)) return 'heading';
  if (tagName === 'li') return 'list';
  if (tagName === 'blockquote') return 'quote';
  if (tagName === 'pre') return 'code';
  return 'paragraph';
};

const getAnchorLabel = (type: ShareAnchorType, preview: string) => {
  if (type === 'heading') return `标题：${preview}`;
  if (type === 'list') return `列表项：${preview}`;
  if (type === 'quote') return `引用：${preview}`;
  if (type === 'code') return `代码块：${preview}`;
  return `段落：${preview}`;
};

const commentCountMap = computed(() => {
  const counts = new Map<string, number>();

  for (const comment of comments.value) {
    if (!comment.anchorKey) {
      continue;
    }

    counts.set(comment.anchorKey, (counts.get(comment.anchorKey) || 0) + 1);
  }

  return counts;
});

const decoratedDocument = computed(() => {
  const sourceHtml = note.value?.contentHtml?.trim();
  if (!sourceHtml || typeof DOMParser === 'undefined') {
    return {
      html: note.value?.contentHtml || '<p><i>暂无内容</i></p>',
      anchors: [] as ShareAnchor[],
    };
  }

  const parser = new DOMParser();
  const doc = parser.parseFromString(`<div data-share-root="1">${sourceHtml}</div>`, 'text/html');
  const root = doc.body.firstElementChild as HTMLElement | null;

  if (!root) {
    return {
      html: sourceHtml,
      anchors: [] as ShareAnchor[],
    };
  }

  const occurrenceMap = new Map<string, number>();
  const anchors: ShareAnchor[] = [];

  root.querySelectorAll(anchorSelector).forEach((element) => {
    const parentAnchor = element.parentElement?.closest(anchorSelector);
    if (parentAnchor) {
      return;
    }

    const tagName = element.tagName.toLowerCase();
    const text = normalizeAnchorText(element.textContent || '');
    if (!text) {
      return;
    }

    const preview = text.slice(0, 120);
    const occurrenceSeed = `${tagName}|${preview.toLowerCase()}`;
    const occurrence = (occurrenceMap.get(occurrenceSeed) || 0) + 1;
    occurrenceMap.set(occurrenceSeed, occurrence);

    const key = `${tagName}-${hashString(`${occurrenceSeed}|${occurrence}`)}`;
    const type = getAnchorType(tagName);
    const label = getAnchorLabel(type, preview);
    const commentCount = commentCountMap.value.get(key) || 0;

    element.classList.add('share-anchor-block');
    element.setAttribute('id', `anchor-${key}`);
    element.setAttribute('data-share-anchor-key', key);
    element.setAttribute('data-share-anchor-label', label);
    element.setAttribute('data-share-anchor-type', type);

    if (commentCount > 0) {
      element.classList.add('has-comments');
      element.setAttribute('data-comment-count-label', `${commentCount} 条评论`);
    }

    if (activeAnchor.value?.key === key) {
      element.classList.add('is-active');
    }

    anchors.push({
      key,
      type,
      label,
      preview,
      commentCount,
    });
  });

  return {
    html: root.innerHTML,
    anchors,
  };
});

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

const commentInputTitle = computed(() => {
  if (!activeAnchor.value) {
    return '发表评论';
  }

  return '发表评论到当前段落';
});

const activeAnchorSummary = computed(() => activeAnchor.value?.label || '');

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
  const target = document.getElementById(`anchor-${anchorKey}`);
  if (!target) {
    message.warning('原段落已变更，暂时无法定位');
    return;
  }

  activeAnchor.value = availableAnchors.value.find((anchor) => anchor.key === anchorKey) || null;
  target.scrollIntoView({ behavior: 'smooth', block: 'center' });
};

const handleAnchorClick = (event: MouseEvent) => {
  if (!note.value?.allowComment) {
    return;
  }

  const target = event.target as HTMLElement | null;
  const linkTarget = target?.closest('a');
  if (linkTarget) {
    return;
  }

  const anchorElement = target?.closest<HTMLElement>('[data-share-anchor-key]');
  if (!anchorElement) {
    return;
  }

  const anchorKey = anchorElement.dataset.shareAnchorKey;
  if (!anchorKey) {
    return;
  }

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
    lastSavedTime.value = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`;

    if (!isAutoSave) {
      message.success('保存成功');
    }
  } catch (err: any) {
    if (!isAutoSave) {
      message.error(err.response?.data?.message || '保存失败');
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

watch(availableAnchors, (anchors) => {
  if (!activeAnchor.value) {
    return;
  }

  const matchedAnchor = anchors.find((anchor) => anchor.key === activeAnchor.value?.key) || null;
  activeAnchor.value = matchedAnchor;
}, { immediate: true });

const fetchComments = async () => {
  try {
    const response = await api.get<ShareComment[]>(`/public/shares/${token}/comments`, {
      params: extractionCode.value ? { code: extractionCode.value } : undefined,
    });
    comments.value = response.data;
  } catch (err: any) {
    console.error('Failed to fetch comments', err);
    if (err.response?.status === 403) {
      message.error(err.response?.data?.message || '无法加载评论');
    }
  }
};

const submitComment = async () => {
  if (!newCommentContent.value.trim()) {
    message.warning('评论内容不能为空');
    return;
  }

  submittingComment.value = true;
  try {
    await api.post(`/public/shares/${token}/comments`, {
      content: newCommentContent.value.trim(),
      authorName: newCommentAuthor.value.trim() || '匿名用户',
      code: extractionCode.value || undefined,
      anchorKey: activeAnchor.value?.key || undefined,
      anchorType: activeAnchor.value?.type || undefined,
      anchorLabel: activeAnchor.value?.label || undefined,
      anchorPreview: activeAnchor.value?.preview || undefined,
    });

    message.success(activeAnchor.value ? '段落评论已发布' : '评论成功');
    newCommentContent.value = '';
    await fetchComments();
  } catch (err: any) {
    message.error(err.response?.data?.message || '评论失败');
  } finally {
    submittingComment.value = false;
  }
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

    if (note.value.title) {
      document.title = `${note.value.title} - SmartNote`;
    }

    if (note.value.allowComment) {
      await fetchComments();
    }
  } catch (err: any) {
    if (err.response?.status === 403) {
      message.error(err.response?.data?.message || '提取码错误');
    } else {
      error.value = err.response?.data?.message || '无法获取分享内容，链接可能已失效';
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
    error.value = err.response?.data?.message || '无法获取分享内容，链接可能已失效';
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
        <div class="editor-tip">当前分享已开启协同编辑，登录用户可实时协作；下方预览区支持段落定位评论。</div>
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
          class="markdown-body content-area interactive-content"
          v-html="decoratedContentHtml"
          @click="handleAnchorClick"
        ></div>
      </div>

      <div v-if="note.allowComment" class="comments-section">
        <div class="comments-head">
          <div>
            <h3 class="comments-title">评论协作</h3>
            <p class="comments-subtitle">支持全文评论，也支持挂到具体段落、标题或列表项上。</p>
          </div>
          <a-tag color="processing">{{ comments.length }} 条评论</a-tag>
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
              <button type="button" class="clear-anchor-btn" @click="activeAnchor = null">改为全文评论</button>
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
            <span class="comment-tip">
              点击文档中的段落后再发表评论，可以形成段落级评论。
            </span>
            <a-button type="primary" :loading="submittingComment" @click="submitComment">发表评论</a-button>
          </div>
        </div>

        <div class="comments-list">
          <a-empty v-if="comments.length === 0" description="暂无评论，来留下第一条吧" />
          <div v-for="comment in comments" :key="comment.id" class="comment-item">
            <div class="comment-header">
              <div class="comment-header-main">
                <span class="comment-author">{{ comment.authorName }}</span>
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

            <div v-if="comment.anchorPreview" class="comment-anchor-preview">
              {{ comment.anchorPreview }}
            </div>
            <div class="comment-content">{{ comment.content }}</div>
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
  background: rgba(219, 234, 254, 0.82);
  border-color: rgba(37, 99, 235, 0.28);
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.12);
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
  .comments-head,
  .comment-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
