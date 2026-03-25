<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { message } from 'ant-design-vue';
import { basicSetup } from 'codemirror';
import { Annotation, EditorSelection, EditorState, RangeSet } from '@codemirror/state';
import { Decoration, EditorView, ViewPlugin } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import MarkdownIt from 'markdown-it';
import * as Y from 'yjs';
import type { Awareness } from 'y-protocols/awareness';
import { yCollab } from 'y-codemirror.next';
import api from '../api';
import { StompYjsProvider } from '../lib/StompYjsProviderSecure';

const props = defineProps<{
  modelValue: string;
  noteId?: number;
  currentUser?: string;
  collab?: boolean;
  shareToken?: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'update:contentHtml', value: string): void;
}>();

type ViewMode = 'edit' | 'split' | 'preview';
type ConnectionState = 'connecting' | 'live' | 'offline' | 'local';
type Collaborator = {
  id: number;
  name: string;
  color: string;
  colorLight: string;
};

const editorHostRef = ref<HTMLElement | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);

const editorView = shallowRef<EditorView | null>(null);
const provider = shallowRef<StompYjsProvider | null>(null);
const ydoc = shallowRef<Y.Doc | null>(null);
const yText = shallowRef<Y.Text | null>(null);
const undoManager = shallowRef<Y.UndoManager | null>(null);

const currentContent = ref(props.modelValue ?? '');
const connectionState = ref<ConnectionState>(props.collab ? 'connecting' : 'local');
const viewMode = ref<ViewMode>(window.innerWidth < 960 ? 'edit' : 'split');
const collaborators = ref<Collaborator[]>([]);
const syncingDocument = ref(Boolean(props.collab));
const draggingFiles = ref(false);

const clientId = Math.random().toString(36).slice(2, 10);
const userLabel = props.currentUser?.trim() || `协作者-${clientId.slice(0, 4)}`;

let htmlEmitTimer: ReturnType<typeof setTimeout> | null = null;

const md = new MarkdownIt({
  breaks: true,
  linkify: true,
  html: false,
});

const renderedHtml = computed(() => {
  if (!currentContent.value.trim()) {
    return '<p class="preview-empty">开始输入内容，即可在这里实时预览。</p>';
  }

  return md.render(currentContent.value);
});

const connectionLabel = computed(() => {
  if (connectionState.value === 'local') return '本地草稿';
  if (connectionState.value === 'connecting') return '协同同步中';
  if (connectionState.value === 'live') return '协同服务已连接';
  return '协同离线';
});

const charCount = computed(() => currentContent.value.length);
const wordCount = computed(() => {
  const trimmed = currentContent.value.trim();
  if (!trimmed) return 0;
  return trimmed.split(/\s+/).length;
});

const hashString = (value: string) => {
  let hash = 0;

  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }

  return hash;
};

const baseHue = hashString(`${props.noteId ?? 'note'}-${userLabel}`) % 360;
const localColor = `hsl(${baseHue}, 74%, 42%)`;
const localColorLight = `hsla(${baseHue}, 74%, 42%, 0.16)`;

const collabPresenceAnnotation = Annotation.define<number[]>();

const formatPresenceLabel = (names: string[]) => {
  if (names.length === 0) {
    return '';
  }

  if (names.length === 1) {
    return `${names[0]} 正在编辑`;
  }

  if (names.length === 2) {
    return `${names[0]}、${names[1]} 正在编辑`;
  }

  return `${names[0]}、${names[1]} 等 ${names.length} 人正在编辑`;
};

type PresenceLine = {
  lineFrom: number;
  label: string;
  accent: string;
  soft: string;
};

const createPresencePlugin = (awareness: Awareness, ytext: Y.Text) => ViewPlugin.fromClass(class {
  decorations = RangeSet.of<Decoration>([]);
  private readonly view: EditorView;

  private readonly listener: ({ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] }) => void;

  constructor(view: EditorView) {
    this.view = view;
    this.listener = ({ added, updated, removed }) => {
      const changedClients = added.concat(updated, removed);
      if (changedClients.findIndex((id) => id !== awareness.doc.clientID) >= 0) {
        this.view.dispatch({
          annotations: [collabPresenceAnnotation.of(changedClients)],
        });
      }
    };

    awareness.on('change', this.listener);
    this.decorations = this.buildDecorations();
  }

  update() {
    this.decorations = this.buildDecorations();
  }

  destroy() {
    awareness.off('change', this.listener);
  }

  private buildDecorations() {
    const doc = this.view.state.doc;
    const ydoc = ytext.doc;
    if (!ydoc) {
      return RangeSet.of<Decoration>([]);
    }

    const lines = new Map<number, PresenceLine & { names: string[] }>();

    awareness.getStates().forEach((state, awarenessId) => {
      if (awarenessId === awareness.doc.clientID) {
        return;
      }

      const cursor = state?.cursor;
      if (!cursor?.anchor || !cursor?.head) {
        return;
      }

      const head = Y.createAbsolutePositionFromRelativePosition(cursor.head, ydoc);
      if (!head || head.type !== ytext) {
        return;
      }

      const line = doc.lineAt(head.index);
      const user = state.user || {};
      const name = String(user.name || '协作者').trim() || '协作者';
      const accent = String(user.color || '#2563eb');
      const soft = String(user.colorLight || 'rgba(37, 99, 235, 0.14)');

      const existing = lines.get(line.from);
      if (existing) {
        existing.names.push(name);
        return;
      }

      lines.set(line.from, {
        lineFrom: line.from,
        names: [name],
        label: '',
        accent,
        soft,
      });
    });

    const lineDecorations = Array.from(lines.values())
      .map((line) => ({
        ...line,
        label: formatPresenceLabel(Array.from(new Set(line.names))),
      }))
      .filter((line) => Boolean(line.label))
      .map((line) => Decoration.line({
        attributes: {
          class: 'cm-collab-presence-line',
          style: `--collab-accent: ${line.accent}; --collab-soft: ${line.soft};`,
          'data-collab-label': line.label,
        },
      }).range(line.lineFrom));

    return RangeSet.of(lineDecorations, true);
  }
}, {
  decorations: (instance) => instance.decorations,
});

const editorTheme = EditorView.theme({
  '&': {
    height: '100%',
    backgroundColor: '#ffffff',
    color: '#1f2937',
    fontSize: '15px',
    lineHeight: '1.7',
  },
  '.cm-scroller': {
    overflow: 'auto',
    fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
  },
  '.cm-content': {
    padding: '24px 28px 48px',
    minHeight: '100%',
    caretColor: '#1d4ed8',
  },
  '.cm-focused': {
    outline: 'none',
  },
  '.cm-line': {
    padding: '0 2px',
  },
  '.cm-gutters': {
    border: 'none',
    backgroundColor: '#f8fafc',
    color: '#94a3b8',
  },
  '.cm-activeLine': {
    backgroundColor: 'rgba(14, 116, 144, 0.06)',
  },
  '.cm-activeLineGutter': {
    backgroundColor: 'rgba(14, 116, 144, 0.10)',
  },
  '.cm-line.cm-collab-presence-line': {
    position: 'relative',
    borderLeft: '3px solid var(--collab-accent)',
    background: 'linear-gradient(90deg, var(--collab-soft), transparent 72%)',
    borderRadius: '10px',
    paddingLeft: '10px',
    marginLeft: '-6px',
    marginRight: '2px',
  },
  '.cm-line.cm-collab-presence-line::before': {
    content: 'attr(data-collab-label)',
    position: 'absolute',
    top: '-1.55em',
    right: '6px',
    display: 'inline-flex',
    alignItems: 'center',
    maxWidth: 'min(60%, 280px)',
    padding: '2px 10px',
    borderRadius: '999px',
    backgroundColor: 'var(--collab-accent)',
    color: '#ffffff',
    fontSize: '11px',
    fontWeight: '700',
    lineHeight: '1.4',
    letterSpacing: '0.01em',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    boxShadow: '0 10px 18px rgba(15, 23, 42, 0.14)',
    pointerEvents: 'none',
  },
  '.cm-selectionBackground': {
    backgroundColor: 'rgba(37, 99, 235, 0.18) !important',
  },
  '.cm-ySelectionInfo': {
    borderRadius: '999px',
    padding: '2px 8px',
    fontFamily: 'inherit',
    fontWeight: '600',
    top: '-1.35em',
    boxShadow: '0 8px 18px rgba(15, 23, 42, 0.14)',
    opacity: 1,
  },
  '.cm-ySelectionCaret': {
    marginLeft: '-1px',
  },
  '.cm-ySelectionCaretDot': {
    width: '0.55em',
    height: '0.55em',
    top: '-0.3em',
    left: '-0.28em',
  },
});

const scheduleHtmlEmit = (content: string) => {
  if (htmlEmitTimer) {
    clearTimeout(htmlEmitTimer);
  }

  htmlEmitTimer = setTimeout(() => {
    emit('update:contentHtml', md.render(content));
  }, 120);
};

const syncContentState = () => {
  const nextValue = yText.value?.toString() ?? '';
  currentContent.value = nextValue;
  emit('update:modelValue', nextValue);
  scheduleHtmlEmit(nextValue);
};

const refreshCollaborators = () => {
  const awareness = provider.value?.awareness;
  if (!awareness) {
    collaborators.value = [];
    return;
  }

  const nextCollaborators: Collaborator[] = [];

  awareness.getStates().forEach((state, awarenessId) => {
    if (awarenessId === awareness.doc.clientID) return;
    if (!state?.user) return;

    nextCollaborators.push({
      id: awarenessId,
      name: state.user.name || '协作者',
      color: state.user.color || '#2563eb',
      colorLight: state.user.colorLight || 'rgba(37, 99, 235, 0.16)',
    });
  });

  collaborators.value = nextCollaborators;
};

const insertRange = (from: number, to: number, value: string, selectionFrom: number, selectionTo: number) => {
  if (!editorView.value) return;

  editorView.value.dispatch({
    changes: { from, to, insert: value },
    selection: EditorSelection.range(selectionFrom, selectionTo),
    scrollIntoView: true,
  });

  editorView.value.focus();
};

const wrapSelection = (prefix: string, suffix = prefix, placeholder = '内容') => {
  if (!editorView.value) return;

  const { from, to, empty } = editorView.value.state.selection.main;
  const selectedText = editorView.value.state.sliceDoc(from, to);
  const content = empty ? placeholder : selectedText;
  const insert = `${prefix}${content}${suffix}`;
  const selectionFrom = from + prefix.length;
  const selectionTo = selectionFrom + content.length;

  insertRange(from, to, insert, selectionFrom, selectionTo);
};

const prefixSelectedLines = (prefixBuilder: (index: number) => string) => {
  if (!editorView.value) return;

  const state = editorView.value.state;
  const { from, to } = state.selection.main;
  const firstLine = state.doc.lineAt(from);
  const lastLine = state.doc.lineAt(to);

  const lines: string[] = [];
  for (let lineNumber = firstLine.number; lineNumber <= lastLine.number; lineNumber += 1) {
    const line = state.doc.line(lineNumber);
    lines.push(`${prefixBuilder(lineNumber - firstLine.number)}${line.text}`);
  }

  const insert = lines.join('\n');
  insertRange(firstLine.from, lastLine.to, insert, firstLine.from, firstLine.from + insert.length);
};

const insertCodeBlock = () => {
  if (!editorView.value) return;

  const { from, to, empty } = editorView.value.state.selection.main;
  const selectedText = editorView.value.state.sliceDoc(from, to);
  const body = empty ? '代码' : selectedText;
  const insert = `\n\`\`\`\n${body}\n\`\`\`\n`;
  const selectionFrom = from + 5;
  const selectionTo = selectionFrom + body.length;

  insertRange(from, to, insert, selectionFrom, selectionTo);
};

const insertLink = () => {
  if (!editorView.value) return;

  const { from, to, empty } = editorView.value.state.selection.main;
  const label = empty ? '链接文本' : editorView.value.state.sliceDoc(from, to);
  const url = 'https://';
  const insert = `[${label}](${url})`;
  const selectionFrom = from + label.length + 3;
  const selectionTo = selectionFrom + url.length;

  insertRange(from, to, insert, selectionFrom, selectionTo);
};

const undo = () => {
  undoManager.value?.undo();
  editorView.value?.focus();
};

const redo = () => {
  undoManager.value?.redo();
  editorView.value?.focus();
};

const triggerUploadPicker = () => {
  fileInputRef.value?.click();
};

const insertUploadedFiles = (entries: Array<[string, string]>) => {
  if (!editorView.value || entries.length === 0) return;

  const markdownText = entries
    .map(([name, url]) => {
      const isImage = /\.(png|jpe?g|gif|webp|svg|bmp)$/i.test(name);
      return isImage ? `![${name}](${url})` : `[${name}](${url})`;
    })
    .join('\n');

  const { from, to } = editorView.value.state.selection.main;
  const prefix = from === 0 ? '' : '\n';
  const suffix = '\n';
  const insert = `${prefix}${markdownText}${suffix}`;
  const cursor = from + insert.length;

  insertRange(from, to, insert, cursor, cursor);
};

const uploadFiles = async (fileList: FileList | File[]) => {
  const files = Array.from(fileList);
  if (files.length === 0) return;

  const formData = new FormData();
  files.forEach((file) => formData.append('file[]', file));

  try {
    const response = await api.post('/files/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });

    const successMap = response.data?.data?.succMap ?? {};
    const uploadedEntries = Object.entries(successMap) as Array<[string, string]>;

    if (uploadedEntries.length > 0) {
      insertUploadedFiles(uploadedEntries);
      message.success(`已上传 ${uploadedEntries.length} 个文件`);
    }

    const failedFiles = response.data?.data?.errFiles ?? [];
    if (failedFiles.length > 0) {
      message.warning(`以下文件上传失败：${failedFiles.join(', ')}`);
    }
  } catch (error) {
    message.error('文件上传失败');
  }
};

const handleFileInputChange = async (event: Event) => {
  const target = event.target as HTMLInputElement;
  const files = target.files;
  if (files) {
    await uploadFiles(files);
  }

  target.value = '';
};

const handleDragOver = (event: DragEvent) => {
  event.preventDefault();
  draggingFiles.value = true;
};

const handleDragLeave = (event: DragEvent) => {
  if (event.currentTarget === event.target) {
    draggingFiles.value = false;
  }
};

const handleDrop = async (event: DragEvent) => {
  event.preventDefault();
  draggingFiles.value = false;

  const files = event.dataTransfer?.files;
  if (files && files.length > 0) {
    await uploadFiles(files);
  }
};

const handlePaste = async (event: ClipboardEvent) => {
  const files = event.clipboardData?.files;
  if (!files || files.length === 0) return;

  event.preventDefault();
  await uploadFiles(files);
};

const buildEditor = () => {
  if (!editorHostRef.value || !yText.value || editorView.value) return;

  const presencePlugin = provider.value?.awareness ? createPresencePlugin(provider.value.awareness, yText.value) : null;

  const extensions = [
    basicSetup,
    EditorView.lineWrapping,
    markdown(),
    editorTheme,
    ...(presencePlugin ? [presencePlugin] : []),
    yCollab(yText.value, provider.value?.awareness, {
      undoManager: undoManager.value || new Y.UndoManager(yText.value),
    }),
  ];

  const state = EditorState.create({
    doc: yText.value.toString(),
    extensions,
  });

  editorView.value = new EditorView({
    state,
    parent: editorHostRef.value,
  });

  editorView.value.dom.addEventListener('dragover', handleDragOver);
  editorView.value.dom.addEventListener('dragleave', handleDragLeave);
  editorView.value.dom.addEventListener('drop', handleDrop);
  editorView.value.dom.addEventListener('paste', handlePaste);
};

onMounted(async () => {
  ydoc.value = new Y.Doc();
  yText.value = ydoc.value.getText('content');
  undoManager.value = new Y.UndoManager(yText.value);
  yText.value.observe(syncContentState);

  if (props.collab && props.noteId) {
    provider.value = new StompYjsProvider({
      noteId: props.noteId,
      doc: ydoc.value,
      clientId,
      user: userLabel,
      color: localColor,
      colorLight: localColorLight,
      shareToken: props.shareToken,
      onStatusChange: (status) => {
        connectionState.value = status;
      },
      onAwarenessChange: refreshCollaborators,
    });
  } else {
    connectionState.value = 'local';
  }

  buildEditor();

  if (!props.collab || !props.noteId) {
    if (props.modelValue) {
      ydoc.value.transact(() => {
        yText.value?.insert(0, props.modelValue);
      }, 'seed');
    }
    syncingDocument.value = false;
    syncContentState();
    return;
  }

  const shouldSeed = await provider.value!.seedDecision;

  if (shouldSeed && props.modelValue && yText.value && yText.value.length === 0) {
    ydoc.value.transact(() => {
      yText.value?.insert(0, props.modelValue);
    }, 'seed');
  }

  syncingDocument.value = false;
  syncContentState();
  refreshCollaborators();
});

onBeforeUnmount(() => {
  if (htmlEmitTimer) {
    clearTimeout(htmlEmitTimer);
  }

  if (editorView.value) {
    editorView.value.dom.removeEventListener('dragover', handleDragOver);
    editorView.value.dom.removeEventListener('dragleave', handleDragLeave);
    editorView.value.dom.removeEventListener('drop', handleDrop);
    editorView.value.dom.removeEventListener('paste', handlePaste);
    editorView.value.destroy();
  }

  yText.value?.unobserve(syncContentState);
  provider.value?.destroy();
  ydoc.value?.destroy();
});
</script>

<template>
  <div class="markdown-editor-shell">
    <div class="editor-toolbar">
      <div class="toolbar-group">
        <button type="button" class="toolbar-btn" @click="prefixSelectedLines(() => '# ')">标题1</button>
        <button type="button" class="toolbar-btn" @click="prefixSelectedLines(() => '## ')">标题2</button>
        <button type="button" class="toolbar-btn" @click="wrapSelection('**')">加粗</button>
        <button type="button" class="toolbar-btn" @click="wrapSelection('*')">斜体</button>
        <button type="button" class="toolbar-btn" @click="wrapSelection('~~')">删除线</button>
        <button type="button" class="toolbar-btn" @click="wrapSelection('`')">行内码</button>
        <button type="button" class="toolbar-btn" @click="insertCodeBlock">代码块</button>
        <button type="button" class="toolbar-btn" @click="insertLink">链接</button>
        <button type="button" class="toolbar-btn" @click="prefixSelectedLines(() => '- ')">无序</button>
        <button type="button" class="toolbar-btn" @click="prefixSelectedLines((index) => `${index + 1}. `)">有序</button>
        <button type="button" class="toolbar-btn" @click="prefixSelectedLines(() => '- [ ] ')">任务</button>
        <button type="button" class="toolbar-btn" @click="prefixSelectedLines(() => '> ')">引用</button>
        <button type="button" class="toolbar-btn" @click="triggerUploadPicker">上传</button>
      </div>

      <div class="toolbar-group toolbar-group-right">
        <button type="button" class="toolbar-btn" @click="undo">撤销</button>
        <button type="button" class="toolbar-btn" @click="redo">重做</button>
        <button
          type="button"
          class="toolbar-btn"
          :class="{ active: viewMode === 'edit' }"
          @click="viewMode = 'edit'"
        >
          编辑
        </button>
        <button
          type="button"
          class="toolbar-btn"
          :class="{ active: viewMode === 'split' }"
          @click="viewMode = 'split'"
        >
          分栏
        </button>
        <button
          type="button"
          class="toolbar-btn"
          :class="{ active: viewMode === 'preview' }"
          @click="viewMode = 'preview'"
        >
          预览
        </button>
      </div>
    </div>

    <div class="editor-statusbar">
      <div class="status-left">
        <span class="status-badge" :class="connectionState">
          <span class="status-dot"></span>
          {{ connectionLabel }}
        </span>
        <span v-if="syncingDocument" class="status-hint">正在等待协作文档状态...</span>
        <span v-else class="status-hint">{{ charCount }} 字符 / {{ wordCount }} 词</span>
      </div>

      <div class="status-right">
        <span
          v-for="collaborator in collaborators"
          :key="collaborator.id"
          class="collaborator-pill"
          :style="{ borderColor: collaborator.color, backgroundColor: collaborator.colorLight, color: collaborator.color }"
        >
          {{ collaborator.name }}
        </span>
      </div>
    </div>

    <div class="editor-workspace" :class="[`mode-${viewMode}`, { dragging: draggingFiles }]">
      <div v-if="viewMode !== 'preview'" class="editor-pane">
        <div ref="editorHostRef" class="editor-host"></div>
      </div>

      <div v-if="viewMode !== 'edit'" class="preview-pane">
        <div class="preview-scroll">
          <div class="markdown-preview" v-html="renderedHtml"></div>
        </div>
      </div>

      <div v-if="draggingFiles" class="drag-mask">
        <div class="drag-panel">拖拽文件到此处上传，并自动插入 Markdown 链接</div>
      </div>

      <div v-if="syncingDocument" class="sync-mask">
        <a-spin />
        <span>正在同步协作文档...</span>
      </div>
    </div>

    <input
      ref="fileInputRef"
      type="file"
      class="hidden-file-input"
      multiple
      @change="handleFileInputChange"
    />
  </div>
</template>

<style scoped>
.markdown-editor-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background:
    radial-gradient(circle at top right, rgba(15, 118, 110, 0.10), transparent 30%),
    linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  border-left: 1px solid rgba(148, 163, 184, 0.16);
}

.editor-toolbar,
.editor-statusbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(18px);
}

.editor-toolbar {
  border-bottom: 1px solid rgba(148, 163, 184, 0.20);
}

.editor-statusbar {
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  padding-top: 8px;
  padding-bottom: 8px;
}

.toolbar-group,
.status-left,
.status-right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar-group-right {
  justify-content: flex-end;
}

.toolbar-btn {
  border: 1px solid rgba(148, 163, 184, 0.24);
  background: rgba(255, 255, 255, 0.92);
  color: #1f2937;
  border-radius: 999px;
  padding: 7px 12px;
  min-width: 42px;
  font-size: 13px;
  line-height: 1;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.04);
}

.toolbar-btn.active,
.toolbar-btn:hover {
  border-color: rgba(13, 148, 136, 0.34);
  background: rgba(240, 253, 250, 0.98);
}

.editor-workspace {
  position: relative;
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 18px;
  padding: 18px;
}

.editor-workspace.mode-edit {
  grid-template-columns: 1fr;
}

.editor-workspace.mode-preview {
  grid-template-columns: 1fr;
}

.editor-workspace.mode-split {
  grid-template-columns: minmax(0, 1.08fr) minmax(320px, 0.92fr);
}

.editor-pane,
.preview-pane {
  min-height: 0;
  overflow: hidden;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow:
    0 18px 45px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.editor-host,
.preview-scroll {
  height: 100%;
  min-height: 0;
}

.preview-scroll {
  overflow: auto;
  padding: 28px 30px 44px;
}

.markdown-preview {
  max-width: 840px;
  margin: 0 auto;
  color: #1f2937;
  line-height: 1.75;
}

.markdown-preview :deep(h1),
.markdown-preview :deep(h2),
.markdown-preview :deep(h3) {
  line-height: 1.25;
  color: #0f172a;
}

.markdown-preview :deep(h1) {
  margin-top: 0;
  font-size: 2rem;
}

.markdown-preview :deep(h2) {
  margin-top: 1.8rem;
  font-size: 1.5rem;
}

.markdown-preview :deep(p),
.markdown-preview :deep(ul),
.markdown-preview :deep(ol),
.markdown-preview :deep(blockquote),
.markdown-preview :deep(pre) {
  margin: 0 0 1rem;
}

.markdown-preview :deep(code) {
  padding: 0.14rem 0.4rem;
  border-radius: 0.35rem;
  background: rgba(15, 23, 42, 0.06);
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
}

.markdown-preview :deep(pre) {
  padding: 1rem 1.1rem;
  overflow: auto;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 1rem;
}

.markdown-preview :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.markdown-preview :deep(blockquote) {
  margin-left: 0;
  padding: 0.1rem 0 0.1rem 1rem;
  border-left: 4px solid #14b8a6;
  color: #475569;
  background: rgba(20, 184, 166, 0.05);
}

.markdown-preview :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 1rem;
}

.markdown-preview :deep(th),
.markdown-preview :deep(td) {
  border: 1px solid rgba(148, 163, 184, 0.3);
  padding: 0.65rem 0.85rem;
}

.preview-empty {
  color: #94a3b8;
  text-align: center;
  padding: 3rem 0;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.status-badge.local {
  color: #475569;
  background: rgba(226, 232, 240, 0.86);
}

.status-badge.connecting {
  color: #9a3412;
  background: rgba(254, 215, 170, 0.68);
}

.status-badge.live {
  color: #065f46;
  background: rgba(167, 243, 208, 0.65);
}

.status-badge.offline {
  color: #991b1b;
  background: rgba(254, 202, 202, 0.72);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
}

.status-hint {
  font-size: 12px;
  color: #64748b;
}

.collaborator-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid currentColor;
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 12px;
  font-weight: 700;
}

.drag-mask,
.sync-mask {
  position: absolute;
  inset: 18px;
  border-radius: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 5;
}

.drag-mask {
  background: rgba(15, 118, 110, 0.14);
  border: 2px dashed rgba(15, 118, 110, 0.55);
}

.drag-panel {
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.94);
  padding: 18px 26px;
  font-weight: 700;
  color: #115e59;
}

.sync-mask {
  flex-direction: column;
  gap: 12px;
  background: rgba(248, 250, 252, 0.86);
  color: #0f172a;
  font-weight: 600;
}

.hidden-file-input {
  display: none;
}

@media (max-width: 1100px) {
  .editor-workspace.mode-split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .editor-toolbar,
  .editor-statusbar {
    padding-left: 12px;
    padding-right: 12px;
  }

  .editor-workspace {
    padding: 12px;
  }

  .drag-mask,
  .sync-mask {
    inset: 12px;
  }

  .preview-scroll {
    padding: 22px 18px 34px;
  }
}
</style>
