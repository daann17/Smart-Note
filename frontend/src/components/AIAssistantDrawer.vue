<template>
  <a-drawer
    title="🤖 SmartNote AI 助手"
    placement="right"
    :closable="true"
    :open="visible"
    @update:open="$emit('update:visible', $event)"
    width="400"
    :bodyStyle="{ padding: 0, display: 'flex', flexDirection: 'column' }"
  >
    <div class="chat-container">
      <div class="chat-messages" ref="messagesContainer">
        <div 
          v-for="(msg, index) in messages" 
          :key="index"
          :class="['message-wrapper', msg.role === 'user' ? 'message-user' : 'message-ai']"
        >
          <div class="message-bubble">
            <template v-if="msg.role === 'ai'">
              <!-- Render Markdown for AI messages -->
              <div class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
            </template>
            <template v-else>
              {{ msg.content }}
            </template>
          </div>
        </div>
        <div v-if="isTyping" class="message-wrapper message-ai">
          <div class="message-bubble typing-indicator">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
          </div>
        </div>
      </div>
      
      <div class="chat-input-area">
        <div v-if="currentNoteId" class="context-indicator">
          <span class="context-tag">
            <file-text-outlined /> 当前笔记上下文中
          </span>
        </div>
        <a-textarea
          v-model:value="inputMessage"
          placeholder="问我关于笔记的问题，或者让我帮忙总结..."
          :auto-size="{ minRows: 2, maxRows: 5 }"
          @pressEnter="handleSend"
        />
        <div class="chat-actions">
          <a-button type="primary" :loading="isTyping" @click="handleSend">发送</a-button>
        </div>
      </div>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import { FileTextOutlined } from '@ant-design/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import MarkdownIt from 'markdown-it';
import { message } from 'ant-design-vue';

const props = defineProps<{
  visible: boolean;
}>();

const emit = defineEmits(['update:visible']);

const route = useRoute();
const router = useRouter();
const currentNoteId = ref<number | null>(null);

// Sync currentNoteId from route
watch(() => route.query.noteId, (newId) => {
  currentNoteId.value = newId ? Number(newId) : null;
}, { immediate: true });

const md = new MarkdownIt({ breaks: true, linkify: true });
const renderMarkdown = (text: string) => {
  return md.render(text || '');
};

interface Message {
  role: 'user' | 'ai';
  content: string;
}

const messages = ref<Message[]>([
  { role: 'ai', content: '你好！我是你的智能助手。你可以问我关于当前笔记的问题，或者让我帮你总结、寻找灵感。' }
]);
const inputMessage = ref('');
const isTyping = ref(false);
const messagesContainer = ref<HTMLElement | null>(null);

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const handleSend = async (e?: KeyboardEvent) => {
  if (e && e.shiftKey) return; // Allow Shift+Enter for new line
  if (e) e.preventDefault();
  
  const text = inputMessage.value.trim();
  if (!text) return;

  const token = localStorage.getItem('token');
  if (!token) {
    message.warning('登录状态已失效，请重新登录');
    localStorage.removeItem('token');
    await router.push('/login');
    return;
  }
  
  messages.value.push({ role: 'user', content: text });
  inputMessage.value = '';
  isTyping.value = true;
  scrollToBottom();

  try {
    const aiMessageIndex = messages.value.length;
    messages.value.push({ role: 'ai', content: '' });

    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        message: text,
        currentNoteId: currentNoteId.value
      })
    });

    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        message.warning('登录状态已过期，请重新登录');
        localStorage.removeItem('token');
        await router.push('/login');
      }
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder('utf-8');

    if (reader) {
      isTyping.value = false; // Stop typing animation as stream starts
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        
        const chunk = decoder.decode(value, { stream: true });
        // Server-Sent Events format: data: xxx\n\n
        const lines = chunk.split('\n');
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim(); // removing 'data:' prefix
            if (data) {
              const aiMessage = messages.value[aiMessageIndex];
              if (!aiMessage) continue;
              aiMessage.content += data;
              scrollToBottom();
            }
          }
        }
      }
    }
  } catch (error: any) {
    console.error('Chat error:', error);
    if (error?.message !== 'HTTP error! status: 401' && error?.message !== 'HTTP error! status: 403') {
      message.error('与 AI 助手通信失败，请重试');
    }
    // Remove the empty AI message if failed
    messages.value.pop();
  } finally {
    isTyping.value = false;
    scrollToBottom();
  }
};
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f7fa;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;
}

.message-user .message-bubble {
  background-color: #1677ff;
  color: white;
  border-top-right-radius: 0;
}

.message-ai .message-bubble {
  background-color: white;
  color: #333;
  border-top-left-radius: 0;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.chat-input-area {
  background: white;
  padding: 16px;
  border-top: 1px solid #e8e8e8;
}

.context-indicator {
  margin-bottom: 8px;
}

.context-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #1677ff;
  background: #e6f4ff;
  padding: 2px 8px;
  border-radius: 4px;
}

.chat-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

/* Typing indicator */
.typing-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 12px 16px !important;
}

.dot {
  width: 6px;
  height: 6px;
  background: #bfbfbf;
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* Markdown Styles inside AI bubble */
:deep(.markdown-body p) {
  margin-bottom: 8px;
}
:deep(.markdown-body p:last-child) {
  margin-bottom: 0;
}
:deep(.markdown-body pre) {
  background: #f6f8fa;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
  margin: 8px 0;
}
:deep(.markdown-body code) {
  font-family: source-code-pro, Menlo, Monaco, Consolas, monospace;
  font-size: 85%;
}
</style>
