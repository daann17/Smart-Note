<template>
  <a-drawer
    :title="drawerTitle"
    placement="right"
    :closable="true"
    :open="visible"
    width="440"
    :bodyStyle="{ padding: 0, display: 'flex', flexDirection: 'column' }"
    @update:open="$emit('update:visible', $event)"
  >
    <template #extra>
      <a-button size="small" @click="clearConversation" :disabled="isRequestInFlight || messages.length <= 1">
        清空会话
      </a-button>
    </template>

    <div class="chat-container">
      <div ref="messagesContainer" class="chat-messages">
        <div v-if="messages.length <= 1" class="quick-prompts">
          <div class="quick-prompts-title">你可以这样问</div>
          <div class="quick-prompt-list">
            <button
              v-for="prompt in quickPrompts"
              :key="prompt"
              type="button"
              class="quick-prompt-chip"
              @click="applyQuickPrompt(prompt)"
            >
              {{ prompt }}
            </button>
          </div>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="`${contextStorageKey}-${index}`"
          :class="['message-wrapper', msg.role === 'user' ? 'message-user' : 'message-ai']"
        >
          <div class="message-bubble">
            <template v-if="msg.role === 'assistant'">
              <div class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
              <div v-if="msg.sources?.length" class="message-sources">
                <button
                  v-for="source in msg.sources"
                  :key="`${source.kind}-${source.noteId}`"
                  type="button"
                  class="source-card"
                  @click="openSourceNote(source)"
                >
                  <span class="source-kind" :class="source.kind">
                    {{ source.kind === 'current' ? '当前笔记' : '参考笔记' }}
                  </span>
                  <strong class="source-title">{{ source.title }}</strong>
                  <span v-if="source.updatedAt" class="source-time">{{ source.updatedAt }}</span>
                  <span v-if="source.snippet" class="source-snippet">{{ source.snippet }}</span>
                </button>
              </div>
            </template>
            <template v-else>
              {{ msg.content }}
            </template>
          </div>
        </div>

        <div v-if="showTypingIndicator" class="message-wrapper message-ai">
          <div class="message-bubble typing-indicator">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
          </div>
        </div>
      </div>

      <div class="chat-input-area">
        <div class="context-indicator">
          <span class="context-tag">
            <FileTextOutlined />
            {{ contextLabel }}
          </span>
        </div>

        <a-textarea
          v-model:value="inputMessage"
          placeholder="输入你的问题，AI 会结合当前笔记或知识库内容回答"
          :auto-size="{ minRows: 3, maxRows: 6 }"
          :disabled="isRequestInFlight"
          @pressEnter="handlePressEnter"
        />

        <div class="chat-actions">
          <span class="chat-tip">按 Enter 发送，Shift + Enter 换行</span>
          <a-button v-if="isRequestInFlight" danger @click="stopStreamingResponse">
            停止
          </a-button>
          <a-button v-else type="primary" @click="handleSend">
            发送
          </a-button>
        </div>
      </div>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { FileTextOutlined } from '@ant-design/icons-vue';
import { message } from 'ant-design-vue';
import MarkdownIt from 'markdown-it';
import { useRouter } from 'vue-router';
import { clearSession } from '../utils/session';

type ChatMessage = {
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
};

type ChatSource = {
  noteId: number;
  notebookId: number | null;
  title: string;
  snippet: string;
  updatedAt: string;
  kind: 'current' | 'related';
};

type ChatHistoryPayload = {
  role: 'user' | 'assistant';
  content: string;
};

const props = defineProps<{
  visible: boolean;
  currentNoteId?: number | null;
  currentNoteTitle?: string | null;
}>();

const emit = defineEmits(['update:visible']);

const router = useRouter();
const md = new MarkdownIt({ breaks: true, linkify: true });

const messages = ref<ChatMessage[]>([]);
const inputMessage = ref('');
const isRequestInFlight = ref(false);
const showTypingIndicator = ref(false);
const activeRequestController = ref<AbortController | null>(null);
const isStopRequested = ref(false);
const messagesContainer = ref<HTMLElement | null>(null);

const contextStorageKey = computed(() => (
  props.currentNoteId ? `smartnote:ai-chat:note-${props.currentNoteId}` : 'smartnote:ai-chat:global'
));

const contextLabel = computed(() => (
  props.currentNoteId
    ? `当前笔记上下文：${props.currentNoteTitle?.trim() || '未命名笔记'}`
    : '全局知识库问答'
));

const drawerTitle = computed(() => (
  props.currentNoteId ? 'SmartNote AI 问答' : 'SmartNote AI 助手'
));

const quickPrompts = computed(() => (
  props.currentNoteId
    ? [
        '总结这篇笔记的核心观点',
        '基于当前笔记给我列一个执行清单',
        '当前笔记还缺哪些关键信息？',
      ]
    : [
        '根据我的知识库，最近我主要在关注什么主题？',
        '帮我梳理一下知识库里可能相关的几条线索',
        '如果要继续完善我的项目，下一步可以做什么？',
      ]
));

const renderMarkdown = (text: string) => md.render(text || '');

const createGreeting = (): ChatMessage => ({
  role: 'assistant',
  content: props.currentNoteId
    ? `你好，我已经接入当前笔记 **${props.currentNoteTitle?.trim() || '未命名笔记'}** 的上下文。你可以让我总结、提炼要点、补充思路，或者直接提问。`
    : '你好，我是 SmartNote AI。你可以直接就整个知识库提问，我会结合检索到的笔记内容回答。',
});

const normalizeChatSource = (value: unknown): ChatSource | null => {
  if (!value || typeof value !== 'object') {
    return null;
  }

  const source = value as Partial<ChatSource>;
  if (typeof source.noteId !== 'number' || typeof source.title !== 'string') {
    return null;
  }

  return {
    noteId: source.noteId,
    notebookId: typeof source.notebookId === 'number' ? source.notebookId : null,
    title: source.title,
    snippet: typeof source.snippet === 'string' ? source.snippet : '',
    updatedAt: typeof source.updatedAt === 'string' ? source.updatedAt : '',
    kind: source.kind === 'current' ? 'current' : 'related',
  };
};

const persistMessages = () => {
  localStorage.setItem(contextStorageKey.value, JSON.stringify(messages.value.slice(-20)));
};

const loadMessages = () => {
  const raw = localStorage.getItem(contextStorageKey.value);
  if (!raw) {
    messages.value = [createGreeting()];
    return;
  }

  try {
    const parsed = JSON.parse(raw) as ChatMessage[];
    const normalized = Array.isArray(parsed)
      ? parsed
        .filter((item) => item && (item.role === 'user' || item.role === 'assistant') && typeof item.content === 'string')
        .map((item) => ({
          role: item.role,
          content: item.content,
          sources: Array.isArray(item.sources)
            ? item.sources
              .map((source) => normalizeChatSource(source))
              .filter((source): source is ChatSource => Boolean(source))
            : undefined,
        }))
      : [];
    messages.value = normalized.length > 0 ? normalized : [createGreeting()];
  } catch (_error) {
    messages.value = [createGreeting()];
  }
};

const clearConversation = () => {
  localStorage.removeItem(contextStorageKey.value);
  messages.value = [createGreeting()];
  inputMessage.value = '';
};

const removeMessageAt = (index: number) => {
  if (index >= 0 && index < messages.value.length) {
    messages.value.splice(index, 1);
  }
};

const clearInputMessage = () => {
  inputMessage.value = '';
  queueMicrotask(() => {
    inputMessage.value = '';
  });
  window.requestAnimationFrame(() => {
    inputMessage.value = '';
  });
};

const openSourceNote = async (source: ChatSource) => {
  if (!source.notebookId) {
    return;
  }

  emit('update:visible', false);
  await router.push({
    name: 'notebook',
    params: {
      notebookId: source.notebookId,
    },
    query: {
      noteId: source.noteId,
    },
  });
};

const stopStreamingResponse = () => {
  if (!isRequestInFlight.value) {
    return;
  }

  isStopRequested.value = true;
  showTypingIndicator.value = false;
  activeRequestController.value?.abort();
};

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const buildHistoryPayload = (): ChatHistoryPayload[] => (
  messages.value
    .filter((item, index) => !(index === 0 && item.role === 'assistant'))
    .slice(-8)
    .map((item) => ({ role: item.role, content: item.content }))
);

const redirectToLogin = async () => {
  clearSession();
  await router.push({
    name: 'login',
    query: {
      redirect: `${window.location.pathname}${window.location.search}${window.location.hash}`,
    },
  });
};

const resolveResponseErrorMessage = (payload: string, status: number) => {
  const fallback = status === 403
    ? 'AI 服务当前不可用，请稍后重试'
    : `AI 请求失败（${status}）`;

  if (!payload.trim()) {
    return fallback;
  }

  try {
    const parsed = JSON.parse(payload) as { message?: string };
    if (parsed?.message?.trim()) {
      return parsed.message.trim();
    }
  } catch (_error) {
    // Ignore JSON parse failures and fall back to raw text.
  }

  return payload.trim();
};

const parseSseEvent = (rawEvent: string) => {
  let event = 'chunk';
  const payloadLines: string[] = [];

  rawEvent.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      const value = line.slice(6);
      event = value.startsWith(' ') ? value.slice(1) : value;
      return;
    }

    if (line.startsWith('data:')) {
      const value = line.slice(5);
      payloadLines.push(value.startsWith(' ') ? value.slice(1) : value);
    }
  });

  return {
    event,
    payload: payloadLines.join('\n'),
  };
};

const setMessageSources = (targetIndex: number, sources: ChatSource[]) => {
  const currentMessage = messages.value[targetIndex];
  if (!currentMessage) {
    return false;
  }

  messages.value[targetIndex] = {
    ...currentMessage,
    sources,
  };
  return false;
};

const appendSseChunk = (targetIndex: number, rawEvent: string) => {
  const { event, payload } = parseSseEvent(rawEvent);

  if (event === 'done') {
    return false;
  }

  if (event === 'sources') {
    try {
      const parsed = JSON.parse(payload) as unknown[];
      const sources = Array.isArray(parsed)
        ? parsed
          .map((source) => normalizeChatSource(source))
          .filter((source): source is ChatSource => Boolean(source))
        : [];
      return setMessageSources(targetIndex, sources);
    } catch (_error) {
      return false;
    }
  }

  if (!payload) {
    return false;
  }

  const currentMessage = messages.value[targetIndex];
  if (!currentMessage) {
    return false;
  }

  messages.value[targetIndex] = {
    ...currentMessage,
    content: currentMessage.content + payload,
  };
  return true;
};

const consumeSseStream = async (reader: ReadableStreamDefaultReader<Uint8Array>, targetIndex: number) => {
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let hasReceivedContent = false;

  const processBuffer = (flush = false) => {
    let separatorIndex = buffer.indexOf('\n\n');
    while (separatorIndex !== -1) {
      const rawEvent = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      if (appendSseChunk(targetIndex, rawEvent) && !hasReceivedContent) {
        hasReceivedContent = true;
        showTypingIndicator.value = false;
      }
      separatorIndex = buffer.indexOf('\n\n');
    }

    if (flush && buffer.trim()) {
      if (appendSseChunk(targetIndex, buffer) && !hasReceivedContent) {
        hasReceivedContent = true;
        showTypingIndicator.value = false;
      }
      buffer = '';
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      buffer += decoder.decode();
      processBuffer(true);
      break;
    }

    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    processBuffer(false);
    await scrollToBottom();
  }
};

const handlePressEnter = (event: KeyboardEvent) => {
  if (event.shiftKey) {
    return;
  }
  event.preventDefault();
  window.setTimeout(() => {
    void handleSend();
  }, 0);
};

const handleSend = async () => {
  const text = inputMessage.value.trim();
  if (!text || isRequestInFlight.value) {
    return;
  }

  const token = localStorage.getItem('token');
  if (!token) {
    message.warning('登录状态已失效，请重新登录');
    await redirectToLogin();
    return;
  }

  const historyPayload = buildHistoryPayload();
  const userMessage: ChatMessage = { role: 'user', content: text };

  messages.value.push(userMessage);
  messages.value.push({ role: 'assistant', content: '' });
  const assistantIndex = messages.value.length - 1;
  let assistantMessage: ChatMessage = messages.value[assistantIndex]!;
  clearInputMessage();
  isRequestInFlight.value = true;
  showTypingIndicator.value = true;
  isStopRequested.value = false;
  const requestController = new AbortController();
  activeRequestController.value = requestController;
  persistMessages();
  await scrollToBottom();

  try {
    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      signal: requestController.signal,
      body: JSON.stringify({
        message: text,
        currentNoteId: props.currentNoteId ?? null,
        history: historyPayload,
      }),
    });

    if (response.status === 401) {
      removeMessageAt(assistantIndex);
      message.warning('登录状态已失效，请重新登录');
      await redirectToLogin();
      return;
    }

    if (!response.ok) {
      const errorPayload = await response.text().catch(() => '');
      throw new Error(resolveResponseErrorMessage(errorPayload, response.status));
    }

    if (!response.body) {
      throw new Error('AI 服务没有返回可读取的数据');
    }

    const reader = response.body.getReader();
    await consumeSseStream(reader, assistantIndex);
    assistantMessage = messages.value[assistantIndex] ?? assistantMessage;
    if (!assistantMessage.content.trim()) {
      assistantMessage.content = '这次没有收到有效回复，请重试一次。';
    }

    persistMessages();
  } catch (error: any) {
    assistantMessage = messages.value[assistantIndex] ?? assistantMessage;
    if (isStopRequested.value || error?.name === 'AbortError') {
      if (!assistantMessage.content.trim()) {
        assistantMessage.content = '已停止回答';
      }
      return;
    }

    console.error('AI chat request failed', error);
    const errorMessage = error instanceof Error
      ? error.message
      : '与 AI 助手通信失败，请稍后重试';

    if (!assistantMessage.content.trim()) {
      assistantMessage.content = `抱歉，${errorMessage}`;
    } else {
      assistantMessage.content += '\n\n> 会话中断了，你可以重试上一条问题。';
    }

    message.error(errorMessage);
  } finally {
    isRequestInFlight.value = false;
    showTypingIndicator.value = false;
    if (activeRequestController.value === requestController) {
      activeRequestController.value = null;
    }
    isStopRequested.value = false;
    persistMessages();
    await scrollToBottom();
  }
};

const applyQuickPrompt = (prompt: string) => {
  inputMessage.value = prompt;
};

watch(
  () => props.currentNoteId,
  () => {
    stopStreamingResponse();
    loadMessages();
    void scrollToBottom();
  },
  { immediate: true },
);

watch(
  () => props.visible,
  (visible) => {
    if (!visible) {
      stopStreamingResponse();
      return;
    }

    loadMessages();
    void scrollToBottom();
  },
);
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background:
    radial-gradient(circle at top, rgba(0, 117, 222, 0.06), transparent 28%),
    linear-gradient(180deg, #fbfaf8 0%, #f6f5f4 100%);
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.quick-prompts {
  padding: 14px 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.quick-prompts-title {
  margin-bottom: 10px;
  color: #615d59;
  font-size: 12px;
  font-weight: 700;
}

.quick-prompt-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.quick-prompt-chip {
  border: 1px solid rgba(0, 0, 0, 0.08);
  padding: 8px 12px;
  border-radius: 999px;
  background: #ffffff;
  color: #0075de;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.2s ease, transform 0.2s ease;
}

.quick-prompt-chip:hover {
  background: #f2f9ff;
  transform: translateY(-1px);
}

.message-wrapper {
  display: flex;
  width: 100%;
}

.message-user {
  justify-content: flex-end;
}

.message-ai {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 88%;
  padding: 12px 14px;
  border-radius: 18px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.message-user .message-bubble {
  background: #0075de;
  color: #fff;
  border-top-right-radius: 6px;
  box-shadow: var(--sn-shadow-card);
}

.message-ai .message-bubble {
  background: rgba(255, 255, 255, 0.94);
  color: rgba(0, 0, 0, 0.95);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-top-left-radius: 6px;
  box-shadow: var(--sn-shadow-card);
}

.message-sources {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.source-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 14px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfaf8 100%);
  text-align: left;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}

.source-card:hover {
  transform: translateY(-1px);
  border-color: rgba(0, 117, 222, 0.2);
  box-shadow: var(--sn-shadow-card);
}

.source-kind {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.source-kind.current {
  background: #f2f9ff;
  color: #0075de;
}

.source-kind.related {
  background: rgba(0, 0, 0, 0.05);
  color: #615d59;
}

.source-title {
  color: rgba(0, 0, 0, 0.95);
  font-size: 13px;
  line-height: 1.4;
}

.source-time {
  color: #a39e98;
  font-size: 11px;
}

.source-snippet {
  color: #615d59;
  font-size: 12px;
  line-height: 1.6;
}

.chat-input-area {
  background: rgba(255, 255, 255, 0.96);
  padding: 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.08);
}

.context-indicator {
  margin-bottom: 10px;
}

.context-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #0075de;
  background: #f2f9ff;
  padding: 4px 10px;
  border-radius: 999px;
}

.chat-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.chat-tip {
  color: #615d59;
  font-size: 12px;
}

.typing-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 12px 16px !important;
}

.dot {
  width: 6px;
  height: 6px;
  background: #a39e98;
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) {
  animation-delay: -0.32s;
}

.dot:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes bounce {
  0%, 80%, 100% {
    transform: scale(0);
  }

  40% {
    transform: scale(1);
  }
}

:deep(.markdown-body p) {
  margin-bottom: 8px;
}

:deep(.markdown-body p:last-child) {
  margin-bottom: 0;
}

:deep(.markdown-body ul),
:deep(.markdown-body ol) {
  padding-left: 20px;
  margin: 8px 0;
}

:deep(.markdown-body pre) {
  background: #f6f5f4;
  padding: 10px 12px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 10px 0;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

:deep(.markdown-body code) {
  font-family: "Cascadia Code", Consolas, Monaco, monospace;
  font-size: 85%;
}

@media (max-width: 768px) {
  .chat-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .chat-tip {
    text-align: left;
  }
}
</style>
