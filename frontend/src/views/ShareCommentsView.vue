<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message, Modal } from 'ant-design-vue';
import { ArrowLeftOutlined, LinkOutlined, MessageOutlined, PushpinOutlined } from '@ant-design/icons-vue';
import { useNoteStore, type ShareComment } from '../stores/note';
import { decorateShareContent, type ShareAnchor } from '../utils/shareAnchors';
import { collectShareAnchorCommentCounts, countShareCommentMessages } from '../utils/shareComments';

type ShareInfo = {
  token: string;
  extractionCode: string | null;
  allowComment: boolean;
  allowEdit: boolean;
  expireAt: string | null;
  isActive: boolean;
};

const route = useRoute();
const router = useRouter();
const noteStore = useNoteStore();

const notebookId = computed(() => Number(route.params.notebookId));
const noteId = computed(() => Number(route.params.noteId));

const loading = ref(true);
const shareInfo = ref<ShareInfo | null>(null);
const shareComments = ref<ShareComment[]>([]);
const shareCommentsLoading = ref(false);
const shareCommentFilter = ref<'all' | 'open' | 'resolved'>('all');
const shareCommentActionId = ref<number | null>(null);
const shareCommentDeleteId = ref<number | null>(null);
const sharePreviewActiveAnchorKey = ref<string | null>(null);
const activeShareReplyCommentId = ref<number | null>(null);
const shareReplySubmittingId = ref<number | null>(null);
const shareReplyDrafts = ref<Record<number, string>>({});

const shareUrl = computed(() => (
  shareInfo.value ? `${window.location.origin}/share/${shareInfo.value.token}` : ''
));

const shareIsExpired = computed(() => (
  Boolean(shareInfo.value?.expireAt && new Date(shareInfo.value.expireAt) <= new Date())
));

const shareStatus = computed(() => {
  if (!shareInfo.value) {
    return { color: 'default', text: '未创建分享' };
  }

  if (!shareInfo.value.isActive) {
    return { color: 'default', text: '已关闭' };
  }

  if (shareIsExpired.value) {
    return { color: 'error', text: '已过期' };
  }

  return { color: 'success', text: '生效中' };
});

const formatShareCommentTime = (value: string) => new Date(value).toLocaleString('zh-CN');

const shareCommentStats = computed(() => {
  let resolved = 0;

  for (const comment of shareComments.value) {
    if (comment.resolved) {
      resolved += 1;
    }
  }

  return {
    all: shareComments.value.length,
    open: shareComments.value.length - resolved,
    resolved,
  };
});

const shareCommentMessageCount = computed(() => countShareCommentMessages(shareComments.value));

const filteredShareComments = computed(() => {
  if (shareCommentFilter.value === 'open') {
    return shareComments.value.filter((comment) => !comment.resolved);
  }

  if (shareCommentFilter.value === 'resolved') {
    return shareComments.value.filter((comment) => comment.resolved);
  }

  return shareComments.value;
});

const shareCommentsEmptyText = computed(() => {
  if (shareComments.value.length === 0) {
    return shareInfo.value?.allowComment ? '暂时还没有评论' : '当前分享未开启评论';
  }

  if (shareCommentFilter.value === 'open') {
    return '当前没有待处理评论';
  }

  if (shareCommentFilter.value === 'resolved') {
    return '当前没有已解决评论';
  }

  return '暂无评论';
});

const shareCommentCountMap = computed(() => collectShareAnchorCommentCounts(shareComments.value));

const shareDecoratedDocument = computed(() =>
  decorateShareContent({
    contentHtml: noteStore.currentNote?.contentHtml,
    activeAnchorKey: sharePreviewActiveAnchorKey.value,
    commentCountByKey: shareCommentCountMap.value,
    anchorIdPrefix: 'author-share-anchor',
  }),
);

const shareDecoratedContentHtml = computed(() => shareDecoratedDocument.value.html);
const shareAvailableAnchors = computed(() => shareDecoratedDocument.value.anchors);

const shareCommentAnchorState = computed(() => {
  const states = new Map<string, { present: boolean; anchor?: ShareAnchor }>();

  for (const comment of shareComments.value) {
    if (!comment.anchorKey) {
      continue;
    }

    const matchedAnchor = shareAvailableAnchors.value.find((anchor) => anchor.key === comment.anchorKey);
    states.set(comment.anchorKey, {
      present: Boolean(matchedAnchor),
      anchor: matchedAnchor,
    });
  }

  return states;
});

const activeSharePreviewAnchor = computed(() => (
  shareAvailableAnchors.value.find((anchor) => anchor.key === sharePreviewActiveAnchorKey.value) || null
));

const loadShareComments = async () => {
  shareCommentsLoading.value = true;
  try {
    shareComments.value = await noteStore.getShareComments(noteId.value);
  } catch (error: any) {
    shareComments.value = [];
    message.error(error.response?.data?.message || '评论加载失败');
  } finally {
    shareCommentsLoading.value = false;
  }
};

const loadPageData = async () => {
  loading.value = true;
  sharePreviewActiveAnchorKey.value = null;
  shareCommentFilter.value = 'all';
  shareCommentActionId.value = null;
  shareCommentDeleteId.value = null;
  activeShareReplyCommentId.value = null;
  shareReplySubmittingId.value = null;
  shareReplyDrafts.value = {};

  try {
    const [, share] = await Promise.all([
      noteStore.getNoteDetail(noteId.value),
      noteStore.getShare(noteId.value),
    ]);

    shareInfo.value = share;

    if (shareInfo.value) {
      await loadShareComments();
    } else {
      shareComments.value = [];
    }
  } catch (error: any) {
    shareInfo.value = null;
    shareComments.value = [];
    message.error(error.response?.data?.message || '评论区加载失败');
  } finally {
    loading.value = false;
  }
};

const focusShareCommentAnchor = async (anchorKey: string | null) => {
  sharePreviewActiveAnchorKey.value = anchorKey;

  if (!anchorKey) {
    return;
  }

  await nextTick();
  const target = document.getElementById(`author-share-anchor-${anchorKey}`);
  if (!target) {
    message.warning('当前锚点已不存在，可能是笔记内容发生了变化');
    return;
  }

  target.scrollIntoView({ behavior: 'smooth', block: 'center' });
};

const handleSharePreviewAnchorClick = (event: MouseEvent) => {
  const target = event.target as HTMLElement | null;
  if (target?.closest('a')) {
    return;
  }

  const anchorElement = target?.closest<HTMLElement>('[data-share-anchor-key]');
  const anchorKey = anchorElement?.dataset.shareAnchorKey;
  if (!anchorKey) {
    return;
  }

  void focusShareCommentAnchor(anchorKey);
};

const handleToggleShareCommentResolved = async (comment: ShareComment) => {
  if (shareCommentActionId.value === comment.id || shareCommentDeleteId.value === comment.id) {
    return;
  }

  shareCommentActionId.value = comment.id;
  try {
    const updatedComment = await noteStore.resolveShareComment(noteId.value, comment.id, !comment.resolved);
    await loadShareComments();
    message.success(updatedComment.resolved ? '评论已标记为已解决' : '评论已重新打开');
  } catch (error: any) {
    message.error(error.response?.data?.message || '评论状态更新失败');
  } finally {
    shareCommentActionId.value = null;
  }
};

const resetShareReplyDraft = (commentId: number) => {
  if (activeShareReplyCommentId.value === commentId) {
    activeShareReplyCommentId.value = null;
  }

  delete shareReplyDrafts.value[commentId];
};

const handleDeleteShareComment = (comment: ShareComment) => {
  if (shareCommentDeleteId.value === comment.id || shareCommentActionId.value === comment.id) {
    return;
  }

  const isReply = comment.parentCommentId !== null;
  const replyCount = comment.replies?.length || 0;

  Modal.confirm({
    title: isReply ? '删除这条回复？' : '删除这条评论？',
    content: !isReply && replyCount > 0
      ? `删除后会同时移除这条评论下的 ${replyCount} 条回复，且无法恢复。`
      : '删除后无法恢复。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      shareCommentDeleteId.value = comment.id;
      try {
        await noteStore.deleteShareComment(noteId.value, comment.id);

        if (activeShareReplyCommentId.value === comment.id) {
          resetShareReplyDraft(comment.id);
        }

        await loadShareComments();
        message.success(isReply ? '回复已删除' : '评论已删除');
      } catch (error: any) {
        message.error(error.response?.data?.message || '删除失败');
      } finally {
        shareCommentDeleteId.value = null;
      }
    },
  });
};

const toggleShareReplyEditor = (comment: ShareComment) => {
  if (activeShareReplyCommentId.value === comment.id) {
    activeShareReplyCommentId.value = null;
    return;
  }

  activeShareReplyCommentId.value = comment.id;
  shareReplyDrafts.value[comment.id] ||= '';
};

const handleSubmitShareReply = async (comment: ShareComment) => {
  if (!shareInfo.value?.allowComment) {
    message.warning('当前分享未开启评论，无法继续回复');
    return;
  }

  const content = shareReplyDrafts.value[comment.id]?.trim();
  if (!content) {
    message.warning('回复内容不能为空');
    return;
  }

  shareReplySubmittingId.value = comment.id;
  try {
    await noteStore.replyToShareComment(noteId.value, {
      content,
      parentCommentId: comment.id,
    });

    resetShareReplyDraft(comment.id);
    await loadShareComments();
    message.success('作者回复已发送');
  } catch (error: any) {
    message.error(error.response?.data?.message || '回复发送失败');
  } finally {
    shareReplySubmittingId.value = null;
  }
};

const handleCopyShareLink = async () => {
  if (!shareUrl.value) {
    return;
  }

  await navigator.clipboard.writeText(shareUrl.value);
  message.success('分享链接已复制');
};

const handleBackToEditor = () => {
  router.push({
    name: 'notebook',
    params: {
      notebookId: notebookId.value,
    },
    query: {
      noteId: noteId.value,
    },
  });
};

watch(shareAvailableAnchors, (anchors) => {
  if (!sharePreviewActiveAnchorKey.value) {
    return;
  }

  const exists = anchors.some((anchor) => anchor.key === sharePreviewActiveAnchorKey.value);
  if (!exists) {
    sharePreviewActiveAnchorKey.value = null;
  }
}, { immediate: true });

onMounted(() => {
  void loadPageData();
});
</script>

<template>
  <div class="share-comments-page">
    <div class="share-comments-shell">
      <div class="share-comments-header">
        <div class="share-comments-header-main">
          <a-button type="text" shape="circle" @click="handleBackToEditor">
            <template #icon><ArrowLeftOutlined /></template>
          </a-button>
          <div class="share-comments-title-block">
            <div class="share-comments-kicker">评论区</div>
            <h1>{{ noteStore.currentNote?.title || '评论区' }}</h1>
            <p>分享页收到的评论、回复与处理状态统一收拢到这里，避免继续堆在分享设置弹窗里。</p>
          </div>
        </div>
        <div class="share-comments-header-actions">
          <a-tag :color="shareStatus.color">{{ shareStatus.text }}</a-tag>
          <a-button v-if="shareInfo" @click="handleCopyShareLink">
            <template #icon><LinkOutlined /></template>
            复制分享链接
          </a-button>
          <a v-if="shareUrl" :href="shareUrl" target="_blank" rel="noreferrer" class="share-comments-open-link">
            打开公开分享页
          </a>
        </div>
      </div>

      <div v-if="loading" class="share-comments-loading">
        <a-spin size="large" tip="正在加载评论区..." />
      </div>

      <div v-else-if="!shareInfo" class="share-comments-empty">
        <a-result status="info" title="该笔记尚未创建分享链接" sub-title="先回到编辑页生成分享，再管理评论。">
          <template #extra>
            <a-button type="primary" @click="handleBackToEditor">返回编辑页</a-button>
          </template>
        </a-result>
      </div>

      <template v-else>
        <div class="share-summary-grid">
          <div class="share-summary-card">
            <span class="share-summary-label">分享状态</span>
            <strong>{{ shareStatus.text }}</strong>
            <small>{{ shareInfo.allowComment ? '已开放评论协作' : '当前未开放新评论' }}</small>
          </div>
          <div class="share-summary-card">
            <span class="share-summary-label">评论总数</span>
            <strong>{{ shareCommentMessageCount }}</strong>
            <small>{{ shareCommentStats.open }} 条待处理，{{ shareCommentStats.resolved }} 条已解决</small>
          </div>
          <div class="share-summary-card">
            <span class="share-summary-label">可评论锚点</span>
            <strong>{{ shareAvailableAnchors.length }}</strong>
            <small>{{ shareInfo.allowEdit ? '已开启协同编辑' : '当前仅评论协作' }}</small>
          </div>
        </div>

        <div class="share-manage-layout">
          <section class="share-preview-panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">
                  <PushpinOutlined />
                  <span>评论锚点预览</span>
                </div>
                <p>点击左侧文档中的标题、段落、列表或代码块，可快速定位评论挂载位置。</p>
              </div>
              <a-tag color="processing">{{ shareAvailableAnchors.length }} 个定位块</a-tag>
            </div>

            <div v-if="activeSharePreviewAnchor" class="share-preview-active">
              <span>{{ activeSharePreviewAnchor.label }}</span>
              <button type="button" class="share-preview-clear" @click="void focusShareCommentAnchor(null)">
                清除定位
              </button>
            </div>

            <div
              class="share-preview-body markdown-body"
              v-html="shareDecoratedContentHtml"
              @click="handleSharePreviewAnchorClick"
            ></div>
          </section>

          <section class="share-comments-panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">
                  <MessageOutlined />
                  <span>评论线程</span>
                </div>
                <p>作者可以在这里处理待解决评论、回复协作者，或清理不再需要的讨论。</p>
              </div>
              <a-tag color="processing">{{ shareCommentMessageCount }} 条留言</a-tag>
            </div>

            <div class="share-comments-toolbar">
              <button
                type="button"
                class="share-filter-btn"
                :class="{ active: shareCommentFilter === 'all' }"
                @click="shareCommentFilter = 'all'"
              >
                全部 {{ shareCommentStats.all }}
              </button>
              <button
                type="button"
                class="share-filter-btn"
                :class="{ active: shareCommentFilter === 'open' }"
                @click="shareCommentFilter = 'open'"
              >
                待处理 {{ shareCommentStats.open }}
              </button>
              <button
                type="button"
                class="share-filter-btn"
                :class="{ active: shareCommentFilter === 'resolved' }"
                @click="shareCommentFilter = 'resolved'"
              >
                已解决 {{ shareCommentStats.resolved }}
              </button>
            </div>

            <div v-if="filteredShareComments.length === 0 && shareComments.length > 0" class="share-comments-empty-tip">
              {{ shareCommentsEmptyText }}
            </div>

            <a-spin :spinning="shareCommentsLoading">
              <a-empty v-if="filteredShareComments.length === 0" :description="shareCommentsEmptyText" />
              <div v-else class="share-comment-list">
                <div
                  v-for="comment in filteredShareComments"
                  :key="comment.id"
                  class="share-comment-item"
                  :class="{ resolved: comment.resolved }"
                >
                  <div class="share-comment-meta">
                    <div class="share-comment-meta-main">
                      <span class="share-comment-author">{{ comment.authorName }}</span>
                      <a-tag v-if="comment.authorComment" color="gold">作者</a-tag>
                    </div>
                    <span>{{ formatShareCommentTime(comment.createdAt) }}</span>
                  </div>

                  <div class="share-comment-state-row">
                    <div class="share-comment-state-main">
                      <a-tag :color="comment.resolved ? 'success' : 'processing'">
                        {{ comment.resolved ? '已解决' : '待处理' }}
                      </a-tag>
                      <span v-if="comment.resolved && comment.resolvedAt" class="share-comment-state-text">
                        {{ comment.resolvedBy || '作者' }} 于 {{ formatShareCommentTime(comment.resolvedAt) }} 标记
                      </span>
                    </div>
                    <button
                      type="button"
                      class="share-comment-toggle-btn"
                      :disabled="shareCommentActionId === comment.id || shareCommentDeleteId === comment.id"
                      @click="void handleToggleShareCommentResolved(comment)"
                    >
                      {{
                        shareCommentActionId === comment.id
                          ? '处理中...'
                          : comment.resolved
                            ? '重新打开'
                            : '标记已解决'
                      }}
                    </button>
                  </div>

                  <button
                    v-if="comment.anchorKey"
                    type="button"
                    class="share-comment-anchor-btn"
                    :class="{
                      missing: !shareCommentAnchorState.get(comment.anchorKey)?.present,
                      active: sharePreviewActiveAnchorKey === comment.anchorKey,
                    }"
                    @click="void focusShareCommentAnchor(comment.anchorKey)"
                  >
                    {{ comment.anchorLabel || '段落评论' }}
                  </button>
                  <a-tag v-else color="default">全文评论</a-tag>

                  <div v-if="comment.anchorPreview" class="share-comment-preview">
                    {{ comment.anchorPreview }}
                  </div>

                  <div class="share-comment-content">{{ comment.content }}</div>

                  <div class="share-comment-actions">
                    <button
                      v-if="shareInfo.allowComment"
                      type="button"
                      class="share-comment-reply-btn"
                      :class="{ active: activeShareReplyCommentId === comment.id }"
                      :disabled="shareCommentDeleteId === comment.id"
                      @click="toggleShareReplyEditor(comment)"
                    >
                      {{ activeShareReplyCommentId === comment.id ? '取消回复' : '作者回复' }}
                    </button>
                    <button
                      type="button"
                      class="share-comment-delete-btn"
                      :disabled="shareCommentDeleteId === comment.id || shareCommentActionId === comment.id"
                      @click="handleDeleteShareComment(comment)"
                    >
                      {{ shareCommentDeleteId === comment.id ? '删除中...' : '删除评论' }}
                    </button>
                    <span v-if="comment.replies.length > 0" class="share-comment-reply-count">
                      {{ comment.replies.length }} 条回复
                    </span>
                  </div>

                  <div v-if="comment.replies.length > 0" class="share-comment-replies">
                    <div v-for="reply in comment.replies" :key="reply.id" class="share-comment-reply-item">
                      <div class="share-comment-meta">
                        <div class="share-comment-meta-main">
                          <span class="share-comment-author">{{ reply.authorName }}</span>
                          <a-tag v-if="reply.authorComment" color="gold">作者</a-tag>
                        </div>
                        <div class="share-comment-meta-side">
                          <span>{{ formatShareCommentTime(reply.createdAt) }}</span>
                          <button
                            type="button"
                            class="share-comment-delete-btn"
                            :disabled="shareCommentDeleteId === reply.id"
                            @click="handleDeleteShareComment(reply)"
                          >
                            {{ shareCommentDeleteId === reply.id ? '删除中...' : '删除' }}
                          </button>
                        </div>
                      </div>
                      <div class="share-comment-content">{{ reply.content }}</div>
                    </div>
                  </div>

                  <div v-if="activeShareReplyCommentId === comment.id" class="share-comment-reply-editor">
                    <a-textarea
                      v-model:value="shareReplyDrafts[comment.id]"
                      placeholder="补充说明或回复这条评论..."
                      :rows="3"
                    />
                    <div class="share-comment-reply-editor-actions">
                      <span class="share-comment-state-text">回复会继续挂在当前评论对应的位置下。</span>
                      <div class="share-comment-reply-editor-buttons">
                        <a-button @click="resetShareReplyDraft(comment.id)">取消</a-button>
                        <a-button
                          type="primary"
                          :loading="shareReplySubmittingId === comment.id"
                          @click="void handleSubmitShareReply(comment)"
                        >
                          发送回复
                        </a-button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </a-spin>
          </section>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.share-comments-page {
  min-height: 100vh;
  padding: 28px;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.1), transparent 24%),
    radial-gradient(circle at bottom right, rgba(15, 118, 110, 0.12), transparent 26%),
    linear-gradient(180deg, #f8fbff 0%, #f3f6fb 100%);
}

.share-comments-shell {
  max-width: 1440px;
  margin: 0 auto;
}

.share-comments-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
}

.share-comments-header-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.share-comments-title-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.share-comments-kicker {
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
}

.share-comments-title-block h1 {
  margin: 0;
  color: #0f172a;
  font-size: 30px;
  line-height: 1.2;
}

.share-comments-title-block p {
  margin: 0;
  max-width: 720px;
  color: #64748b;
  line-height: 1.7;
}

.share-comments-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.share-comments-open-link {
  color: #2563eb;
  font-weight: 600;
}

.share-comments-loading,
.share-comments-empty {
  padding: 56px 24px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 22px 52px rgba(15, 23, 42, 0.08);
}

.share-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.share-summary-card {
  padding: 18px 20px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.06);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.share-summary-label {
  color: #64748b;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.share-summary-card strong {
  color: #0f172a;
  font-size: 28px;
  line-height: 1;
}

.share-summary-card small {
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.share-manage-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(380px, 0.85fr);
  gap: 20px;
  align-items: start;
}

.share-preview-panel,
.share-comments-panel {
  padding: 20px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 22px 52px rgba(15, 23, 42, 0.08);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.panel-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #0f172a;
  font-size: 18px;
  font-weight: 700;
}

.panel-head p {
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.share-preview-active {
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(239, 246, 255, 0.92);
  color: #1d4ed8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.share-preview-clear {
  border: none;
  background: transparent;
  color: #2563eb;
  font-weight: 700;
  cursor: pointer;
}

.share-preview-body {
  max-height: calc(100vh - 280px);
  overflow: auto;
  padding-right: 6px;
}

.share-preview-body :deep(.share-anchor-block) {
  position: relative;
  margin: 0 -10px;
  padding: 8px 10px;
  border-radius: 14px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.share-preview-body :deep(.share-anchor-block:hover) {
  background: rgba(239, 246, 255, 0.84);
  border-color: rgba(37, 99, 235, 0.14);
}

.share-preview-body :deep(.share-anchor-block.is-active) {
  background: linear-gradient(90deg, rgba(219, 234, 254, 0.96) 0%, rgba(239, 246, 255, 0.92) 100%);
  border-color: rgba(37, 99, 235, 0.28);
  box-shadow:
    inset 4px 0 0 #2563eb,
    0 12px 26px rgba(37, 99, 235, 0.12);
}

.share-preview-body :deep(.share-anchor-block.has-comments)::after {
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
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  box-shadow: 0 10px 16px rgba(15, 118, 110, 0.18);
}

.share-comments-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.share-comments-empty-tip {
  margin-bottom: 12px;
  font-size: 12px;
  color: #8c8c8c;
}

.share-filter-btn {
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #fff;
  color: #475569;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.share-filter-btn.active {
  border-color: #1677ff;
  background: #eff6ff;
  color: #1d4ed8;
}

.share-comment-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.share-comment-item {
  padding: 14px 16px;
  border-radius: 18px;
  background: #fff;
  border: 1px solid #eef2f6;
}

.share-comment-item.resolved {
  background: #fcfffc;
  border-color: #d9f7be;
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

.share-comment-meta-main,
.share-comment-meta-side,
.share-comment-state-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.share-comment-author {
  font-size: 13px;
  font-weight: 700;
  color: #1677ff;
}

.share-comment-state-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.share-comment-state-text {
  font-size: 12px;
  color: #8c8c8c;
}

.share-comment-toggle-btn,
.share-comment-reply-btn,
.share-comment-delete-btn {
  border: none;
  background: transparent;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  padding: 0;
}

.share-comment-toggle-btn,
.share-comment-reply-btn {
  color: #1677ff;
}

.share-comment-delete-btn {
  color: #dc2626;
}

.share-comment-toggle-btn:disabled,
.share-comment-reply-btn:disabled,
.share-comment-delete-btn:disabled {
  cursor: wait;
}

.share-comment-toggle-btn:disabled,
.share-comment-reply-btn:disabled {
  color: #94a3b8;
}

.share-comment-delete-btn:disabled {
  color: #fca5a5;
}

.share-comment-anchor-btn {
  margin-bottom: 8px;
  padding: 0;
  border: none;
  background: transparent;
  text-align: left;
  font-size: 12px;
  font-weight: 700;
  color: #0958d9;
  cursor: pointer;
}

.share-comment-anchor-btn.active {
  color: #1d4ed8;
}

.share-comment-anchor-btn.missing {
  color: #b45309;
}

.share-comment-preview {
  margin-bottom: 8px;
  padding: 10px 12px;
  border-left: 3px solid #91caff;
  border-radius: 8px;
  background: #f0f7ff;
  font-size: 12px;
  line-height: 1.6;
  color: #595959;
}

.share-comment-content {
  color: #1f2937;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.share-comment-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.share-comment-reply-btn.active {
  color: #1d4ed8;
}

.share-comment-reply-count {
  color: #64748b;
  font-size: 12px;
}

.share-comment-replies {
  margin-top: 16px;
  padding-left: 16px;
  border-left: 2px solid rgba(191, 219, 254, 0.9);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.share-comment-reply-item {
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.92);
  border: 1px solid rgba(226, 232, 240, 0.88);
}

.share-comment-reply-editor {
  margin-top: 16px;
  padding: 16px;
  border-radius: 18px;
  background: rgba(239, 246, 255, 0.62);
  border: 1px solid rgba(147, 197, 253, 0.3);
}

.share-comment-reply-editor-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.share-comment-reply-editor-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

@media (max-width: 1200px) {
  .share-summary-grid,
  .share-manage-layout {
    grid-template-columns: 1fr;
  }

  .share-preview-body {
    max-height: 420px;
  }
}

@media (max-width: 768px) {
  .share-comments-page {
    padding: 16px;
  }

  .share-comments-header,
  .panel-head,
  .share-comment-state-row,
  .share-comment-reply-editor-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .share-comments-header-actions {
    justify-content: flex-start;
  }

  .share-comments-title-block h1 {
    font-size: 24px;
  }

  .share-preview-active {
    flex-direction: column;
    align-items: flex-start;
  }
}

/* Notion-inspired overrides */
.share-comments-page {
  max-width: 1360px;
  margin: 0 auto;
  padding: 28px 24px 40px;
  background: transparent;
}

.share-comments-shell {
  max-width: none;
}

.share-comments-header {
  margin-bottom: 24px;
}

.share-comments-header-main {
  gap: 16px;
}

.share-comments-header-main :deep(.ant-btn) {
  border: 1px solid var(--sn-border);
  background: rgba(0, 0, 0, 0.04);
}

.share-comments-kicker {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 10px;
  border-radius: var(--sn-radius-pill);
  background: var(--sn-blue-soft);
  color: var(--sn-focus);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.share-comments-title-block h1 {
  margin-top: 6px;
  font-size: clamp(32px, 4vw, 52px);
  line-height: 1.04;
  letter-spacing: -1.4px;
  color: var(--sn-text);
}

.share-comments-title-block p {
  color: var(--sn-text-soft);
}

.share-comments-open-link {
  color: var(--sn-blue);
}

.share-comments-loading,
.share-comments-empty,
.share-summary-card,
.share-preview-panel,
.share-comments-panel {
  border-radius: 20px;
  border: 1px solid var(--sn-border);
  background: var(--sn-surface);
  box-shadow: var(--sn-shadow-card);
}

.share-summary-grid {
  gap: 16px;
  margin-bottom: 20px;
}

.share-summary-card {
  padding: 22px 20px;
}

.share-summary-label {
  color: var(--sn-text-muted);
  letter-spacing: 0.08em;
}

.share-summary-card strong {
  color: var(--sn-text);
  font-size: 32px;
  letter-spacing: -0.8px;
}

.share-summary-card small {
  color: var(--sn-text-soft);
}

.share-manage-layout {
  grid-template-columns: minmax(0, 1.08fr) minmax(380px, 0.92fr);
  gap: 20px;
}

.share-preview-panel,
.share-comments-panel {
  padding: 22px;
}

.panel-title {
  color: var(--sn-text);
  font-size: 20px;
  letter-spacing: -0.2px;
}

.panel-head p {
  color: var(--sn-text-soft);
}

.share-preview-active {
  border-radius: 16px;
  border: 1px solid rgba(0, 117, 222, 0.14);
  background: linear-gradient(180deg, #ffffff 0%, var(--sn-blue-soft) 100%);
  color: var(--sn-focus);
}

.share-preview-clear {
  color: var(--sn-focus);
}

.share-preview-body {
  padding-right: 4px;
}

.share-preview-body :deep(.share-anchor-block:hover) {
  background: rgba(0, 117, 222, 0.05);
  border-color: rgba(0, 117, 222, 0.12);
}

.share-preview-body :deep(.share-anchor-block.is-active) {
  background: linear-gradient(90deg, rgba(242, 249, 255, 0.96) 0%, rgba(255, 255, 255, 0.98) 100%);
  border-color: rgba(0, 117, 222, 0.2);
  box-shadow:
    inset 4px 0 0 var(--sn-blue),
    0 12px 24px rgba(0, 117, 222, 0.08);
}

.share-preview-body :deep(.share-anchor-block.has-comments)::after {
  background: var(--sn-teal);
  box-shadow: 0 10px 16px rgba(42, 157, 153, 0.18);
}

.share-comments-empty-tip {
  color: var(--sn-text-soft);
}

.share-filter-btn {
  border: 1px solid var(--sn-border);
  background: var(--sn-surface-muted);
  color: var(--sn-text-soft);
  padding: 7px 14px;
}

.share-filter-btn.active {
  border-color: rgba(0, 117, 222, 0.18);
  background: var(--sn-blue-soft);
  color: var(--sn-focus);
}

.share-comment-item {
  padding: 16px 18px;
  border-radius: 18px;
  background: #ffffff;
  border: 1px solid var(--sn-border);
  box-shadow: var(--sn-shadow-card);
}

.share-comment-item.resolved {
  background: #fbfff9;
  border-color: rgba(26, 174, 57, 0.18);
}

.share-comment-meta {
  color: var(--sn-text-muted);
}

.share-comment-author {
  color: var(--sn-text);
}

.share-comment-state-text,
.share-comment-reply-count {
  color: var(--sn-text-soft);
}

.share-comment-toggle-btn,
.share-comment-reply-btn,
.share-comment-delete-btn {
  border-radius: var(--sn-radius-pill);
  padding: 6px 12px;
  border: 1px solid var(--sn-border);
  background: #ffffff;
}

.share-comment-toggle-btn,
.share-comment-reply-btn {
  color: var(--sn-focus);
}

.share-comment-delete-btn {
  color: var(--sn-red);
}

.share-comment-anchor-btn {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: var(--sn-radius-pill);
  background: var(--sn-blue-soft);
  color: var(--sn-focus);
}

.share-comment-anchor-btn.missing {
  background: rgba(221, 91, 0, 0.1);
  color: var(--sn-orange);
}

.share-comment-preview {
  border: 1px solid var(--sn-border);
  border-radius: 14px;
  background: var(--sn-bg-soft);
  color: var(--sn-text-soft);
}

.share-comment-content {
  color: var(--sn-text);
}

.share-comment-replies {
  padding-left: 14px;
  border-left: 2px solid rgba(0, 0, 0, 0.06);
}

.share-comment-reply-item,
.share-comment-reply-editor {
  border-radius: 16px;
  border: 1px solid var(--sn-border);
  background: var(--sn-bg-soft);
}

@media (max-width: 1200px) {
  .share-summary-grid,
  .share-manage-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .share-comments-page {
    padding: 16px;
  }

  .share-comments-title-block h1 {
    font-size: clamp(28px, 9vw, 36px);
  }
}
</style>
