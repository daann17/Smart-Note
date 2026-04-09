<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { SearchOutlined, FileTextOutlined, LoadingOutlined } from '@ant-design/icons-vue';
import { useNoteStore } from '../stores/note';
import type { Note } from '../stores/note';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

type HighlightSegment = {
  text: string;
  match: boolean;
};

const router = useRouter();
const noteStore = useNoteStore();

const keyword = ref('');
const results = ref<Note[]>([]);
const searching = ref(false);
const activeIndex = ref(-1);
const searchInputRef = ref<HTMLInputElement | null>(null);

let debounceTimer: ReturnType<typeof setTimeout> | null = null;

const buildHighlightSegments = (text: string, query: string): HighlightSegment[] => {
  const source = text || '未命名笔记';
  const normalizedQuery = query.trim();

  if (!normalizedQuery) {
    return [{ text: source, match: false }];
  }

  const loweredSource = source.toLowerCase();
  const loweredQuery = normalizedQuery.toLowerCase();
  const segments: HighlightSegment[] = [];

  let cursor = 0;
  let matchIndex = loweredSource.indexOf(loweredQuery, cursor);

  while (matchIndex !== -1) {
    if (matchIndex > cursor) {
      segments.push({
        text: source.slice(cursor, matchIndex),
        match: false,
      });
    }

    segments.push({
      text: source.slice(matchIndex, matchIndex + loweredQuery.length),
      match: true,
    });

    cursor = matchIndex + loweredQuery.length;
    matchIndex = loweredSource.indexOf(loweredQuery, cursor);
  }

  if (cursor < source.length) {
    segments.push({
      text: source.slice(cursor),
      match: false,
    });
  }

  return segments.length > 0 ? segments : [{ text: source, match: false }];
};

const doSearch = async (query: string) => {
  if (!query.trim()) {
    results.value = [];
    searching.value = false;
    return;
  }

  searching.value = true;
  try {
    results.value = await noteStore.searchNotes(query.trim()) ?? [];
  } finally {
    searching.value = false;
  }
};

watch(keyword, (value) => {
  activeIndex.value = -1;

  if (debounceTimer) {
    clearTimeout(debounceTimer);
  }

  if (!value.trim()) {
    results.value = [];
    searching.value = false;
    return;
  }

  searching.value = true;
  debounceTimer = setTimeout(() => {
    void doSearch(value);
  }, 300);
});

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    keyword.value = '';
    results.value = [];
    activeIndex.value = -1;
    await nextTick();
    searchInputRef.value?.focus();
    return;
  }

  if (debounceTimer) {
    clearTimeout(debounceTimer);
  }
});

const highlightedResults = computed(() =>
  results.value.map((note) => ({
    ...note,
    titleSegments: buildHighlightSegments(note.title || '未命名笔记', keyword.value),
    notebookName: note.notebook?.name ?? '',
  }))
);

const openNote = (note: Note) => {
  emit('update:open', false);
  router.push({
    name: 'notebook',
    params: { notebookId: note.notebookId ?? note.notebook?.id },
    query: { noteId: note.id },
  });
};

const handleKeydown = (event: KeyboardEvent) => {
  if (!props.open) return;

  if (results.value.length === 0) {
    if (event.key === 'Escape') {
      emit('update:open', false);
    }
    return;
  }

  if (event.key === 'ArrowDown') {
    event.preventDefault();
    activeIndex.value = activeIndex.value < results.value.length - 1 ? activeIndex.value + 1 : 0;
    return;
  }

  if (event.key === 'ArrowUp') {
    event.preventDefault();
    activeIndex.value = activeIndex.value > 0 ? activeIndex.value - 1 : results.value.length - 1;
    return;
  }

  if (event.key === 'Enter') {
    const selected = results.value[activeIndex.value >= 0 ? activeIndex.value : 0];
    if (selected) {
      openNote(selected);
    }
    return;
  }

  if (event.key === 'Escape') {
    emit('update:open', false);
  }
};

onMounted(() => document.addEventListener('keydown', handleKeydown));

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown);
  if (debounceTimer) {
    clearTimeout(debounceTimer);
  }
});
</script>

<template>
  <a-modal
    :open="open"
    :footer="null"
    :closable="false"
    width="640px"
    class="global-search-modal"
    :body-style="{ padding: 0 }"
    @cancel="emit('update:open', false)"
  >
    <div class="search-header">
      <LoadingOutlined v-if="searching" class="search-icon spin" />
      <SearchOutlined v-else class="search-icon" />
      <input
        ref="searchInputRef"
        v-model="keyword"
        class="search-input"
        placeholder="搜索全部笔记... (↑↓ 导航，Enter 打开，Esc 关闭)"
        autocomplete="off"
        spellcheck="false"
      />
    </div>

    <div class="search-divider" />

    <div class="search-results">
      <div v-if="!keyword.trim()" class="search-empty">
        <SearchOutlined class="empty-icon" />
        <span>输入关键词搜索全部笔记</span>
      </div>

      <div v-else-if="!searching && results.length === 0" class="search-empty">
        <FileTextOutlined class="empty-icon" />
        <span>未找到与 "{{ keyword }}" 相关的笔记</span>
      </div>

      <div
        v-for="(note, index) in highlightedResults"
        :key="note.id"
        class="result-item"
        :class="{ active: index === activeIndex }"
        @click="openNote(note)"
        @mouseenter="activeIndex = index"
      >
        <FileTextOutlined class="result-icon" />
        <div class="result-body">
          <span class="result-title">
            <template v-for="(segment, segmentIndex) in note.titleSegments" :key="`${note.id}-${segmentIndex}`">
              <mark v-if="segment.match">{{ segment.text }}</mark>
              <span v-else>{{ segment.text }}</span>
            </template>
          </span>
          <span class="result-meta">
            {{ note.notebookName }}
            <span v-if="note.updatedAt" class="result-date">
              路 {{ new Date(note.updatedAt).toLocaleDateString() }}
            </span>
          </span>
        </div>
        <kbd v-if="index === activeIndex" class="result-enter-hint">↵</kbd>
      </div>
    </div>

    <div v-if="results.length > 0" class="search-footer">
      共 {{ results.length }} 条结果
    </div>
  </a-modal>
</template>

<style>
.global-search-modal .ant-modal-content {
  padding: 0;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: var(--sn-shadow-deep);
}
</style>

<style scoped>
.search-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 20px;
}

.search-icon {
  font-size: 18px;
  color: #a39e98;
  flex-shrink: 0;
  transition: color 0.2s;
}

.search-icon.spin {
  color: #0075de;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.search-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 16px;
  font-weight: 500;
  color: rgba(0, 0, 0, 0.95);
  background: transparent;
  caret-color: #0075de;
}

.search-input::placeholder {
  color: #a39e98;
  font-weight: 400;
}

.search-divider {
  height: 1px;
  background: rgba(0, 0, 0, 0.08);
}

.search-results {
  max-height: 420px;
  overflow-y: auto;
  padding: 8px 0;
}

.search-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 40px 0;
  color: #a39e98;
  font-size: 14px;
}

.empty-icon {
  font-size: 28px;
  opacity: 0.5;
}

.result-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  cursor: pointer;
  transition: background 0.12s;
  border-radius: 0;
}

.result-item:hover,
.result-item.active {
  background: #f2f9ff;
}

.result-icon {
  font-size: 16px;
  color: #a39e98;
  flex-shrink: 0;
}

.result-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.result-title {
  font-size: 14px;
  font-weight: 500;
  color: rgba(0, 0, 0, 0.95);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-title mark {
  background: rgba(0, 117, 222, 0.14);
  color: #0075de;
  border-radius: 2px;
  padding: 0 1px;
}

.result-meta {
  font-size: 12px;
  color: #615d59;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-date {
  color: #a39e98;
}

.result-enter-hint {
  flex-shrink: 0;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 5px;
  padding: 2px 7px;
  font-size: 11px;
  color: #615d59;
  background: #fbfaf8;
  font-family: inherit;
}

.search-footer {
  padding: 8px 20px;
  border-top: 1px solid rgba(0, 0, 0, 0.08);
  font-size: 12px;
  color: #a39e98;
  text-align: right;
}
</style>
