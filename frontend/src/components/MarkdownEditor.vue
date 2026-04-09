<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { message } from 'ant-design-vue';
import { basicSetup } from 'codemirror';
import { Annotation, EditorSelection, EditorState, Prec, RangeSet, StateField } from '@codemirror/state';
import {
  Decoration,
  EditorView,
  GutterMarker,
  ViewPlugin,
  WidgetType,
  keymap,
  lineNumberWidgetMarker,
  type DecorationSet,
} from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import MarkdownIt from 'markdown-it';
// @ts-expect-error — markdown-it-katex 无官方类型声明
import MarkdownItKatex from 'markdown-it-katex';
import 'katex/dist/katex.min.css';
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
type MarkdownImage = {
  from: number;
  to: number;
  alt: string;
  url: string;
  width: number | null;
  height: number | null;
};
type ImagePreset = {
  label: string;
  width: number | null;
};
type ImageEditorDraft = {
  from: number;
  to: number;
  alt: string;
  url: string;
  width: number | null;
  height: number | null;
  source: string;
};

type ImageSize = {
  width: number | null;
  height: number | null;
};
type ResizeHandle = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';
type DeleteRange = {
  from: number;
  to: number;
};

const markdownImageRegex = /!\[(?<alt>[^\]]*)\]\((?<url>[^)\s]+)(?:\s+"[^"]*")?\)(?<attrs>\{[^}\n]+\})?/g;
const imageWidthPresets: ImagePreset[] = [
  { label: '小', width: 280 },
  { label: '中', width: 480 },
  { label: '大', width: 720 },
  { label: '适应', width: null },
];

const editorHostRef = ref<HTMLElement | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);
const workspaceRef = ref<HTMLElement | null>(null);
// Mermaid 渲染目标容器：预览面板的根节点
const previewPaneRef = ref<HTMLElement | null>(null);

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
const imagePreviewVisible = ref(false);
const imagePreviewSrc = ref('');
const imagePreviewAlt = ref('');
const imageEditorVisible = ref(false);
const imageEditorDraft = ref<ImageEditorDraft | null>(null);
const viewportWidth = ref(window.innerWidth);
const workspaceContentWidth = ref(0);
const splitEditorWidth = ref<number | null>(null);
const resizingSplit = ref(false);

const clientId = Math.random().toString(36).slice(2, 10);
const userLabel = props.currentUser?.trim() || `协作者-${clientId.slice(0, 4)}`;

const SPLIT_LAYOUT_BREAKPOINT = 1100;
const SPLIT_DIVIDER_WIDTH = 18;
const MIN_EDITOR_PANE_WIDTH = 360;
const MIN_PREVIEW_PANE_WIDTH = 320;
const DEFAULT_EDITOR_PANE_RATIO = 0.54;

let workspaceResizeObserver: ResizeObserver | null = null;
let removeSplitResizeListeners: (() => void) | null = null;
let previousBodyCursor = '';
let previousBodyUserSelect = '';
let htmlEmitTimer: ReturnType<typeof setTimeout> | null = null;
let mermaidApi: typeof import('mermaid').default | null = null;
let mermaidReady: Promise<typeof import('mermaid').default> | null = null;

// ──────────────────────────────────────────────────────────────────────────────
// Markdown-it 实例：配置扩展插件
// ──────────────────────────────────────────────────────────────────────────────
const md = new MarkdownIt({
  breaks: true,
  linkify: true,
  html: false,
});

// KaTeX 数学公式：支持行内 $...$ 和块级 $$...$$ 语法
md.use(MarkdownItKatex, { throwOnError: false });

// Mermaid 流程图：自定义 fence 渲染器，将 ```mermaid 代码块转换为占位 div，
// 由 renderMermaidBlocks() 在 DOM 更新后异步渲染为 SVG
const defaultFenceRenderer = md.renderer.rules.fence?.bind(md.renderer) ?? (
  (tokens: any[], idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options)
);

md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const lang = (token?.info ?? '').trim();

  if (lang === 'mermaid') {
    // 将图表源码 base64 编码存入 data 属性，避免 HTML 转义问题
    const encoded = btoa(unescape(encodeURIComponent(token?.content ?? '')));
    return `<div class="mermaid-block" data-source="${encoded}"></div>`;
  }

  return defaultFenceRenderer(tokens, idx, options, env, self);
};

// 初始化 Mermaid（关闭自动扫描，由我们手动控制渲染时机）
const escapeHtml = (value: string) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;');

const getMermaid = async () => {
  if (mermaidApi) {
    return mermaidApi;
  }

  if (!mermaidReady) {
    mermaidReady = import('mermaid')
      .then((module) => {
        module.default.initialize({
          startOnLoad: false,
          theme: 'neutral',
          securityLevel: 'strict',
        });
        mermaidApi = module.default;
        return mermaidApi;
      })
      .catch((error) => {
        mermaidReady = null;
        throw error;
      });
  }

  return mermaidReady;
};

// 渲染预览面板中所有待渲染的 Mermaid 占位块
let mermaidIdCounter = 0;
const renderMermaidBlocks = async (container: HTMLElement | null) => {
  if (!container) return;

  const blocks = container.querySelectorAll<HTMLElement>('.mermaid-block[data-source]');
  if (blocks.length === 0) return;

  let mermaidInstance: typeof import('mermaid').default;
  try {
    mermaidInstance = await getMermaid();
  } catch (err) {
    const errorText = escapeHtml(String(err));
    blocks.forEach((block) => {
      block.innerHTML = `<pre class="mermaid-error">${errorText}</pre>`;
      delete block.dataset.source;
    });
    return;
  }

  await Promise.all(Array.from(blocks).map(async (block) => {
    const encoded = block.dataset.source;
    if (!encoded) return;

    try {
      const source = decodeURIComponent(escape(atob(encoded)));
      const id = `mermaid-${++mermaidIdCounter}`;
      const { svg } = await mermaidInstance.render(id, source);
      block.innerHTML = svg;
      // 标记已渲染，避免重复处理
      delete block.dataset.source;
    } catch (err) {
      // 语法错误时显示友好提示而非崩溃
      block.innerHTML = `<pre class="mermaid-error">${escapeHtml(String(err))}</pre>`;
      delete block.dataset.source;
    }
  }));
};

const renderPreviewMermaid = async () => {
  await nextTick();
  await renderMermaidBlocks(previewPaneRef.value);
};

const defaultImageRenderer = md.renderer.rules.image
  ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options));

md.renderer.rules.image = (tokens, index, options, env, self) => {
  const token = tokens[index];
  if (!token) {
    return '';
  }

  const title = token.attrGet('title');
  const widthMatch = title?.match(/(?:^|\s)width=(\d+)/);
  const heightMatch = title?.match(/(?:^|\s)height=(\d+)/);
  const styleRules = ['max-width: 100%'];

  if (widthMatch?.[1]) {
    const matchedWidth = widthMatch[1];
    token.attrSet('data-display-width', matchedWidth);
    styleRules.push(`width: min(100%, ${matchedWidth}px)`);
  }

  if (heightMatch?.[1]) {
    const matchedHeight = heightMatch[1];
    token.attrSet('data-display-height', matchedHeight);
    styleRules.push(`height: ${matchedHeight}px`);
  }

  token.attrSet('style', styleRules.join('; '));
  token.attrSet('title', '');

  const existingClass = token.attrGet('class');
  token.attrSet('class', existingClass ? `${existingClass} markdown-rendered-image` : 'markdown-rendered-image');

  return defaultImageRenderer(tokens, index, options, env, self);
};

const normalizeImageDimension = (value: number | null | undefined) => {
  if (value == null || Number.isNaN(Number(value))) {
    return null;
  }

  const normalized = Math.round(Number(value));
  if (normalized < 80) return 80;
  if (normalized > 2400) return 2400;
  return normalized;
};

const parseImageSizeAttributes = (attrs: string | undefined): ImageSize => {
  const source = attrs ?? '';
  const widthMatch = source.match(/width=(\d+)/);
  const heightMatch = source.match(/height=(\d+)/);

  return {
    width: normalizeImageDimension(widthMatch?.[1] ? Number(widthMatch[1]) : null),
    height: normalizeImageDimension(heightMatch?.[1] ? Number(heightMatch[1]) : null),
  };
};

const buildMarkdownImage = (alt: string, url: string, width: number | null, height: number | null) => {
  const escapedAlt = alt.replace(/]/g, '\\]');
  const trimmedUrl = url.trim();
  const normalizedWidth = normalizeImageDimension(width);
  const normalizedHeight = normalizeImageDimension(height);
  const attrs = [
    normalizedWidth ? `width=${normalizedWidth}` : '',
    normalizedHeight ? `height=${normalizedHeight}` : '',
  ].filter(Boolean).join(' ');

  return `![${escapedAlt}](${trimmedUrl})${attrs ? `{${attrs}}` : ''}`;
};

const transformMarkdownForPreview = (content: string) => content.replace(
  (() => {
    markdownImageRegex.lastIndex = 0;
    return markdownImageRegex;
  })(),
  (_match, alt: string, url: string, attrs?: string) => {
    const size = parseImageSizeAttributes(attrs);
    const titleParts = [
      size.width ? `width=${size.width}` : '',
      size.height ? `height=${size.height}` : '',
    ].filter(Boolean);

    if (titleParts.length === 0) {
      return `![${alt}](${url})`;
    }

    return `![${alt}](${url} "${titleParts.join(' ')}")`;
  },
);

const renderMarkdownContent = (content: string) => md.render(transformMarkdownForPreview(content));

const renderedHtml = computed(() => {
  if (!currentContent.value.trim()) {
    return '<p class="preview-empty">开始输入内容，即可在这里实时预览。</p>';
  }

  return renderMarkdownContent(currentContent.value);
});

const splitIsDraggable = computed(() => (
  viewMode.value === 'split'
  && viewportWidth.value > SPLIT_LAYOUT_BREAKPOINT
  && workspaceContentWidth.value >= MIN_EDITOR_PANE_WIDTH + MIN_PREVIEW_PANE_WIDTH + SPLIT_DIVIDER_WIDTH
));

const splitShouldStack = computed(() => viewMode.value === 'split' && !splitIsDraggable.value);

const clampSplitEditorWidth = (width: number, totalWidth: number) => {
  const maxWidth = totalWidth - MIN_PREVIEW_PANE_WIDTH - SPLIT_DIVIDER_WIDTH;
  return Math.min(Math.max(width, MIN_EDITOR_PANE_WIDTH), maxWidth);
};

const measureWorkspace = () => {
  viewportWidth.value = window.innerWidth;

  if (!workspaceRef.value) {
    workspaceContentWidth.value = 0;
    return;
  }

  const styles = window.getComputedStyle(workspaceRef.value);
  const paddingLeft = Number.parseFloat(styles.paddingLeft || '0');
  const paddingRight = Number.parseFloat(styles.paddingRight || '0');

  workspaceContentWidth.value = Math.max(0, workspaceRef.value.clientWidth - paddingLeft - paddingRight);
};

const requestEditorLayout = () => {
  editorView.value?.requestMeasure();
};

const syncSplitEditorWidth = () => {
  if (!splitIsDraggable.value) {
    splitEditorWidth.value = null;
    return;
  }

  const defaultWidth = Math.round((workspaceContentWidth.value - SPLIT_DIVIDER_WIDTH) * DEFAULT_EDITOR_PANE_RATIO);
  splitEditorWidth.value = clampSplitEditorWidth(
    splitEditorWidth.value ?? defaultWidth,
    workspaceContentWidth.value,
  );
};

const syncSplitLayout = () => {
  measureWorkspace();
  syncSplitEditorWidth();
};

const workspaceStyle = computed<Record<string, string> | undefined>(() => {
  if (!splitIsDraggable.value || splitEditorWidth.value == null) {
    return undefined;
  }

  const editorWidth = clampSplitEditorWidth(splitEditorWidth.value, workspaceContentWidth.value);
  const previewWidth = Math.max(
    MIN_PREVIEW_PANE_WIDTH,
    workspaceContentWidth.value - editorWidth - SPLIT_DIVIDER_WIDTH,
  );

  return {
    gridTemplateColumns: `${editorWidth}px ${SPLIT_DIVIDER_WIDTH}px ${previewWidth}px`,
    '--split-divider-width': `${SPLIT_DIVIDER_WIDTH}px`,
  };
});

const stopSplitResize = () => {
  if (removeSplitResizeListeners) {
    removeSplitResizeListeners();
    removeSplitResizeListeners = null;
  }

  if (!resizingSplit.value) {
    return;
  }

  resizingSplit.value = false;
  document.body.style.cursor = previousBodyCursor;
  document.body.style.userSelect = previousBodyUserSelect;
};

const updateSplitFromClientX = (clientX: number) => {
  if (!workspaceRef.value || !splitIsDraggable.value) {
    return;
  }

  const rect = workspaceRef.value.getBoundingClientRect();
  const styles = window.getComputedStyle(workspaceRef.value);
  const paddingLeft = Number.parseFloat(styles.paddingLeft || '0');
  const pointerOffset = clientX - rect.left - paddingLeft - (SPLIT_DIVIDER_WIDTH / 2);

  splitEditorWidth.value = clampSplitEditorWidth(Math.round(pointerOffset), workspaceContentWidth.value);
  requestEditorLayout();
};

const startSplitResize = (event: PointerEvent) => {
  if (!splitIsDraggable.value) {
    return;
  }

  event.preventDefault();
  stopSplitResize();

  previousBodyCursor = document.body.style.cursor;
  previousBodyUserSelect = document.body.style.userSelect;
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  resizingSplit.value = true;

  updateSplitFromClientX(event.clientX);

  const handlePointerMove = (moveEvent: PointerEvent) => {
    updateSplitFromClientX(moveEvent.clientX);
  };

  const handlePointerUp = () => {
    stopSplitResize();
  };

  window.addEventListener('pointermove', handlePointerMove);
  window.addEventListener('pointerup', handlePointerUp);
  window.addEventListener('pointercancel', handlePointerUp);

  removeSplitResizeListeners = () => {
    window.removeEventListener('pointermove', handlePointerMove);
    window.removeEventListener('pointerup', handlePointerUp);
    window.removeEventListener('pointercancel', handlePointerUp);
  };
};

const handleWindowResize = () => {
  syncSplitLayout();
  requestEditorLayout();
};

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
  '.cm-md-image-widget': {
    margin: '20px 0',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'flex-start',
  },
  '.cm-md-image-frame': {
    display: 'inline-flex',
    flexDirection: 'column',
    alignItems: 'flex-start',
    gap: '10px',
    maxWidth: '100%',
  },
  '.cm-md-image-stage': {
    position: 'relative',
    display: 'inline-flex',
    maxWidth: '100%',
  },
  '.cm-md-image-media': {
    display: 'block',
    width: 'auto',
    maxWidth: '100%',
    padding: '0',
    border: 'none',
    background: 'transparent',
    cursor: 'zoom-in',
  },
  '.cm-md-image-media img': {
    display: 'block',
    margin: '0 auto',
    maxHeight: 'none',
    objectFit: 'fill',
    borderRadius: '18px',
    boxShadow: '0 18px 40px rgba(15, 23, 42, 0.10)',
  },
  '.cm-md-image-resize-overlay': {
    position: 'absolute',
    inset: '0',
    pointerEvents: 'none',
  },
  '.cm-md-resize-handle': {
    position: 'absolute',
    border: 'none',
    background: 'transparent',
    boxShadow: 'none',
    borderRadius: '0',
    pointerEvents: 'auto',
    touchAction: 'none',
  },
  '.cm-md-resize-handle.is-e, .cm-md-resize-handle.is-w': {
    top: '50%',
    width: '18px',
    height: '64px',
    transform: 'translateY(-50%)',
    cursor: 'ew-resize',
  },
  '.cm-md-resize-handle.is-e': {
    right: '-9px',
  },
  '.cm-md-resize-handle.is-w': {
    left: '-9px',
  },
  '.cm-md-resize-handle.is-n, .cm-md-resize-handle.is-s': {
    left: '50%',
    width: '64px',
    height: '18px',
    transform: 'translateX(-50%)',
    cursor: 'ns-resize',
  },
  '.cm-md-resize-handle.is-n': {
    top: '-9px',
  },
  '.cm-md-resize-handle.is-s': {
    bottom: '-9px',
  },
  '.cm-md-resize-handle.is-ne, .cm-md-resize-handle.is-nw, .cm-md-resize-handle.is-se, .cm-md-resize-handle.is-sw': {
    width: '20px',
    height: '20px',
  },
  '.cm-md-resize-handle.is-ne': {
    top: '-10px',
    right: '-10px',
    cursor: 'nesw-resize',
  },
  '.cm-md-resize-handle.is-nw': {
    top: '-10px',
    left: '-10px',
    cursor: 'nwse-resize',
  },
  '.cm-md-resize-handle.is-se': {
    right: '-10px',
    bottom: '-10px',
    cursor: 'nwse-resize',
  },
  '.cm-md-resize-handle.is-sw': {
    left: '-10px',
    bottom: '-10px',
    cursor: 'nesw-resize',
  },
  '.cm-md-image-toolbar': {
    display: 'none',
  },
  '.cm-md-image-widget[data-broken="true"] .cm-md-image-media': {
    cursor: 'default',
  },
  '.cm-md-image-widget[data-broken="true"] .cm-md-image-media img': {
    minHeight: '180px',
    objectFit: 'contain',
    opacity: 0.45,
  },
});

const scheduleHtmlEmit = (content: string) => {
  if (htmlEmitTimer) {
    clearTimeout(htmlEmitTimer);
  }

  htmlEmitTimer = setTimeout(() => {
    emit('update:contentHtml', renderMarkdownContent(content));
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

const parseMarkdownImages = (docText: string) => {
  const matches: MarkdownImage[] = [];
  markdownImageRegex.lastIndex = 0;

  for (const match of docText.matchAll(markdownImageRegex)) {
    const fullText = match[0];
    const alt = match.groups?.alt ?? '';
    const url = match.groups?.url ?? '';
    const size = parseImageSizeAttributes(match.groups?.attrs);

    matches.push({
      from: match.index ?? 0,
      to: (match.index ?? 0) + fullText.length,
      alt,
      url,
      width: size.width,
      height: size.height,
    });
  }

  return matches;
};

const getImageSizeText = (width: number | null, height: number | null) => {
  if (width == null && height == null) {
    return '按容器宽度自适应';
  }

  if (width != null && height != null) {
    return `${width} × ${height}px`;
  }

  if (width != null) {
    return `宽度 ${width}px`;
  }

  return `高度 ${height}px`;
};

const resolveImageRange = (draft: ImageEditorDraft) => {
  const view = editorView.value;
  if (!view) return null;

  const currentDoc = view.state.doc.toString();
  if (currentDoc.slice(draft.from, draft.to) === draft.source) {
    return draft;
  }

  const matchedBySource = parseMarkdownImages(currentDoc).find((image) => (
    buildMarkdownImage(image.alt, image.url, image.width, image.height) === draft.source
  ));

  if (matchedBySource) {
    return {
      ...draft,
      from: matchedBySource.from,
      to: matchedBySource.to,
      source: buildMarkdownImage(matchedBySource.alt, matchedBySource.url, matchedBySource.width, matchedBySource.height),
    };
  }

  return draft;
};

const openImagePreview = (image: Pick<MarkdownImage, 'url' | 'alt'>) => {
  imagePreviewSrc.value = image.url;
  imagePreviewAlt.value = image.alt;
  imagePreviewVisible.value = true;
};

const closeImagePreview = () => {
  imagePreviewVisible.value = false;
  imagePreviewSrc.value = '';
  imagePreviewAlt.value = '';
};

const openImageEditor = (image: MarkdownImage) => {
  imageEditorDraft.value = {
    ...image,
    source: buildMarkdownImage(image.alt, image.url, image.width, image.height),
  };
  imageEditorVisible.value = true;
};

const closeImageEditor = () => {
  imageEditorVisible.value = false;
  imageEditorDraft.value = null;
};

const setImageDraftWidth = (width: number | null) => {
  if (!imageEditorDraft.value) {
    return;
  }

  imageEditorDraft.value = {
    ...imageEditorDraft.value,
    width,
  };
};

const setImageDraftHeight = (height: number | null) => {
  if (!imageEditorDraft.value) {
    return;
  }

  imageEditorDraft.value = {
    ...imageEditorDraft.value,
    height,
  };
};

const updateImageMarkdown = (image: Pick<MarkdownImage, 'from' | 'to' | 'alt' | 'url' | 'width' | 'height'>) => {
  const markdownText = buildMarkdownImage(image.alt, image.url, image.width, image.height);
  const selection = image.from + markdownText.length;
  insertRange(image.from, image.to, markdownText, selection, selection);
};

const resizeImage = (image: MarkdownImage, size: ImageSize) => {
  updateImageMarkdown({
    ...image,
    width: size.width,
    height: size.height,
  });
};

const getImageDeleteRange = (state: EditorState, image: MarkdownImage) => {
  const startLine = state.doc.lineAt(image.from);
  const endLine = state.doc.lineAt(Math.max(image.from, image.to - 1));
  let from = image.from;
  let to = image.to;

  if (startLine.number === endLine.number) {
    const fullLineText = state.doc.sliceString(startLine.from, endLine.to).trim();
    const imageText = state.doc.sliceString(image.from, image.to).trim();

    if (fullLineText === imageText) {
      from = startLine.from;
      to = endLine.to;

      if (to < state.doc.length && state.doc.sliceString(to, to + 1) === '\n') {
        to += 1;
      }
    }
  }

  return { from, to };
};

const removeImage = (image: MarkdownImage) => {
  const view = editorView.value;
  if (!view) return;

  const deleteRange = getImageDeleteRange(view.state, image);
  insertRange(deleteRange.from, deleteRange.to, '', deleteRange.from, deleteRange.from);
};

const shouldDeleteImageAtCursor = (
  state: EditorState,
  image: MarkdownImage,
  cursor: number,
  key: 'Backspace' | 'Delete',
) => {
  const deleteRange = getImageDeleteRange(state, image);

  if (key === 'Backspace') {
    return cursor > deleteRange.from && cursor <= deleteRange.to;
  }

  return cursor >= deleteRange.from && cursor < deleteRange.to;
};

const mergeDeleteRanges = (ranges: DeleteRange[]) => {
  if (ranges.length === 0) {
    return [];
  }

  const sortedRanges = [...ranges].sort((left, right) => left.from - right.from);
  const firstRange = sortedRanges[0];
  if (!firstRange) {
    return [];
  }

  const mergedRanges: DeleteRange[] = [firstRange];

  for (const currentRange of sortedRanges.slice(1)) {
    const lastRange = mergedRanges[mergedRanges.length - 1]!;

    if (currentRange.from <= lastRange.to) {
      lastRange.to = Math.max(lastRange.to, currentRange.to);
      continue;
    }

    mergedRanges.push(currentRange);
  }

  return mergedRanges;
};

const collectImageDeleteRanges = (state: EditorState, key: 'Backspace' | 'Delete') => {
  const images = parseMarkdownImages(state.doc.toString());
  if (images.length === 0) {
    return [];
  }

  const ranges: DeleteRange[] = [];

  state.selection.ranges.forEach((selectionRange) => {
    if (selectionRange.empty) {
      const targetImage = images.find((image) => shouldDeleteImageAtCursor(state, image, selectionRange.from, key));
      if (targetImage) {
        ranges.push(getImageDeleteRange(state, targetImage));
      }
      return;
    }

    const overlappedRanges = images
      .filter((image) => selectionRange.from < image.to && selectionRange.to > image.from)
      .map((image) => getImageDeleteRange(state, image));

    if (overlappedRanges.length === 0) {
      return;
    }

    ranges.push({
      from: Math.min(selectionRange.from, ...overlappedRanges.map((range) => range.from)),
      to: Math.max(selectionRange.to, ...overlappedRanges.map((range) => range.to)),
    });
  });

  return mergeDeleteRanges(ranges);
};

const runImageDeleteCommand = (key: 'Backspace' | 'Delete') => (view: EditorView) => {
  const deleteRanges = collectImageDeleteRanges(view.state, key);
  const firstDeleteRange = deleteRanges[0];
  if (!firstDeleteRange) {
    return false;
  }

  view.dispatch({
    changes: deleteRanges.map((deleteRange) => ({
      from: deleteRange.from,
      to: deleteRange.to,
      insert: '',
    })),
    selection: EditorSelection.cursor(firstDeleteRange.from),
    scrollIntoView: true,
  });

  return true;
};

const findImageCursorTarget = (state: EditorState, key: 'ArrowLeft' | 'ArrowRight') => {
  const selection = state.selection.main;
  if (!selection.empty) {
    return null;
  }

  const cursor = selection.from;
  const images = parseMarkdownImages(state.doc.toString());

  if (key === 'ArrowLeft') {
    const targetImage = images.find((image) => cursor === image.to || (cursor > image.from && cursor < image.to));
    return targetImage ? targetImage.from : null;
  }

  const targetImage = images.find((image) => cursor === image.from || (cursor > image.from && cursor < image.to));
  return targetImage ? targetImage.to : null;
};

const runImageCursorCommand = (key: 'ArrowLeft' | 'ArrowRight') => (view: EditorView) => {
  const target = findImageCursorTarget(view.state, key);
  if (target == null) {
    return false;
  }

  view.dispatch({
    selection: EditorSelection.cursor(target),
    scrollIntoView: true,
  });
  return true;
};

const imageCursorKeymap = Prec.highest(keymap.of([
  {
    key: 'ArrowLeft',
    run: runImageCursorCommand('ArrowLeft'),
  },
  {
    key: 'ArrowRight',
    run: runImageCursorCommand('ArrowRight'),
  },
]));

const imageDeleteKeymap = Prec.highest(keymap.of([
  {
    key: 'Backspace',
    run: runImageDeleteCommand('Backspace'),
  },
  {
    key: 'Delete',
    run: runImageDeleteCommand('Delete'),
  },
  {
    key: 'Mod-Backspace',
    run: runImageDeleteCommand('Backspace'),
  },
  {
    key: 'Mod-Delete',
    run: runImageDeleteCommand('Delete'),
  },
  {
    key: 'Shift-Backspace',
    run: runImageDeleteCommand('Backspace'),
  },
]));

const applyImageEditor = () => {
  const draft = imageEditorDraft.value;
  const view = editorView.value;

  if (!draft || !view) {
    return;
  }

  if (!draft.url.trim()) {
    message.warning('图片地址不能为空');
    return;
  }

  const resolvedDraft = resolveImageRange(draft);
  if (!resolvedDraft) {
    message.warning('未找到要更新的图片');
    return;
  }

  updateImageMarkdown({
    from: resolvedDraft.from,
    to: resolvedDraft.to,
    alt: draft.alt.trim(),
    url: draft.url.trim(),
    width: normalizeImageDimension(draft.width),
    height: normalizeImageDimension(draft.height),
  });

  closeImageEditor();
};

const normalizeImageSize = (size: ImageSize): ImageSize => ({
  width: normalizeImageDimension(size.width),
  height: normalizeImageDimension(size.height),
});

const applyImageElementSizeStyles = (element: HTMLImageElement, size: ImageSize) => {
  element.style.width = size.width == null ? 'auto' : `${size.width}px`;
  element.style.height = size.height == null ? 'auto' : `${size.height}px`;
  element.style.maxWidth = '100%';
};

class MarkdownImageWidget extends WidgetType {
  private readonly image: MarkdownImage;
  private readonly actions: {
    preview: (image: Pick<MarkdownImage, 'url' | 'alt'>) => void;
    edit: (image: MarkdownImage) => void;
    resize: (image: MarkdownImage, size: ImageSize) => void;
    remove: (image: MarkdownImage) => void;
  };

  constructor(
    image: MarkdownImage,
    actions: {
      preview: (image: Pick<MarkdownImage, 'url' | 'alt'>) => void;
      edit: (image: MarkdownImage) => void;
      resize: (image: MarkdownImage, size: ImageSize) => void;
      remove: (image: MarkdownImage) => void;
    },
  ) {
    super();
    this.image = image;
    this.actions = actions;
  }

  eq(other: MarkdownImageWidget) {
    return other.image.alt === this.image.alt
      && other.image.url === this.image.url
      && other.image.width === this.image.width
      && other.image.height === this.image.height;
  }

  toDOM() {
    const root = document.createElement('div');
    root.className = 'cm-md-image-widget';

    const frame = document.createElement('div');
    frame.className = 'cm-md-image-frame';
    root.appendChild(frame);

    const stage = document.createElement('div');
    stage.className = 'cm-md-image-stage';
    frame.appendChild(stage);

    const media = document.createElement('button');
    media.type = 'button';
    media.className = 'cm-md-image-media';
    media.title = '点击查看大图';
    media.addEventListener('click', (event) => {
      event.preventDefault();
      this.actions.preview(this.image);
    });
    stage.appendChild(media);

    const imageElement = document.createElement('img');
    imageElement.src = this.image.url;
    imageElement.alt = this.image.alt || '笔记图片';
    imageElement.loading = 'lazy';
    imageElement.decoding = 'async';
    applyImageElementSizeStyles(imageElement, this.image);
    media.appendChild(imageElement);

    const resizeOverlay = document.createElement('div');
    resizeOverlay.className = 'cm-md-image-resize-overlay';
    stage.appendChild(resizeOverlay);

    const toolbar = document.createElement('div');
    toolbar.className = 'cm-md-image-toolbar';
    frame.appendChild(toolbar);

    const meta = document.createElement('div');
    meta.className = 'cm-md-image-meta';
    toolbar.appendChild(meta);

    const name = document.createElement('span');
    name.className = 'cm-md-image-name';
    name.textContent = this.image.alt || '未命名图片';
    meta.appendChild(name);

    const detail = document.createElement('span');
    detail.className = 'cm-md-image-detail';
    detail.textContent = getImageSizeText(this.image.width, this.image.height);
    meta.appendChild(detail);

    const actions = document.createElement('div');
    actions.className = 'cm-md-image-actions';
    toolbar.appendChild(actions);

    imageWidthPresets.forEach((preset) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'cm-md-image-action cm-md-image-size';
      if (preset.width === this.image.width && this.image.height == null) {
        button.dataset.active = 'true';
      }
      button.textContent = preset.label;
      button.addEventListener('click', (event) => {
        event.preventDefault();
        this.actions.resize(this.image, {
          width: preset.width,
          height: null,
        });
      });
      actions.appendChild(button);
    });

    const editButton = document.createElement('button');
    editButton.type = 'button';
    editButton.className = 'cm-md-image-action';
    editButton.textContent = '设置';
    editButton.addEventListener('click', (event) => {
      event.preventDefault();
      this.actions.edit(this.image);
    });
    actions.appendChild(editButton);

    const deleteButton = document.createElement('button');
    deleteButton.type = 'button';
    deleteButton.className = 'cm-md-image-action danger';
    deleteButton.textContent = '删除';
    deleteButton.addEventListener('click', (event) => {
      event.preventDefault();
      this.actions.remove(this.image);
    });
    actions.appendChild(deleteButton);

    imageElement.addEventListener('error', () => {
      root.dataset.broken = 'true';
      name.textContent = this.image.alt || '图片加载失败';
      detail.textContent = this.image.url;
    });

    let naturalWidth = 0;
    let naturalHeight = 0;

    const syncNaturalSize = () => {
      naturalWidth = imageElement.naturalWidth || naturalWidth;
      naturalHeight = imageElement.naturalHeight || naturalHeight;
    };

    imageElement.addEventListener('load', syncNaturalSize);
    if (imageElement.complete) {
      syncNaturalSize();
    }

    const readCurrentSize = () => {
      const rect = imageElement.getBoundingClientRect();
      const width = Math.round(rect.width || this.image.width || naturalWidth || 480);
      const height = Math.round(rect.height || this.image.height || naturalHeight || 320);
      return {
        width: Math.max(width, 80),
        height: Math.max(height, 80),
      };
    };

    const createHandle = (handle: ResizeHandle) => {
      const handleElement = document.createElement('button');
      handleElement.type = 'button';
      handleElement.className = `cm-md-resize-handle is-${handle}`;
      handleElement.title = '拖拽调整图片尺寸';
      handleElement.addEventListener('pointerdown', (event) => {
        event.preventDefault();
        event.stopPropagation();

        const startX = event.clientX;
        const startY = event.clientY;
        const startSize = readCurrentSize();
        const aspectRatio = startSize.width / Math.max(startSize.height, 1);
        let previewSize: ImageSize = {
          width: this.image.width,
          height: this.image.height,
        };

        const updatePreview = (size: ImageSize) => {
          previewSize = normalizeImageSize(size);
          applyImageElementSizeStyles(imageElement, previewSize);
          detail.textContent = getImageSizeText(previewSize.width, previewSize.height);
        };

        const resolveWidthOnly = (deltaX: number) => {
          if (handle.includes('w')) {
            return startSize.width - deltaX;
          }

          return startSize.width + deltaX;
        };

        const resolveHeightOnly = (deltaY: number) => {
          if (handle.includes('n')) {
            return startSize.height - deltaY;
          }

          return startSize.height + deltaY;
        };

        const onPointerMove = (moveEvent: PointerEvent) => {
          const deltaX = moveEvent.clientX - startX;
          const deltaY = moveEvent.clientY - startY;

          if (handle === 'e' || handle === 'w') {
            updatePreview({
              width: resolveWidthOnly(deltaX),
              height: startSize.height,
            });
            return;
          }

          if (handle === 'n' || handle === 's') {
            updatePreview({
              width: startSize.width,
              height: resolveHeightOnly(deltaY),
            });
            return;
          }

          const widthByX = resolveWidthOnly(deltaX);
          const widthByY = resolveHeightOnly(deltaY) * aspectRatio;
          const useHorizontalDelta = Math.abs(deltaX / Math.max(startSize.width, 1))
            >= Math.abs(deltaY / Math.max(startSize.height, 1));
          const nextWidth = useHorizontalDelta ? widthByX : widthByY;
          const normalizedWidth = Math.max(80, Math.round(nextWidth));
          const normalizedHeight = Math.max(80, Math.round(normalizedWidth / Math.max(aspectRatio, 0.01)));

          updatePreview({
            width: normalizedWidth,
            height: normalizedHeight,
          });
        };

        const finishDrag = () => {
          window.removeEventListener('pointermove', onPointerMove);
          window.removeEventListener('pointerup', onPointerUp);
          document.body.classList.remove('image-resize-active');
          document.body.style.cursor = '';
          this.actions.resize(this.image, previewSize);
        };

        const onPointerUp = () => {
          finishDrag();
        };

        document.body.classList.add('image-resize-active');
        document.body.style.cursor = getComputedStyle(handleElement).cursor;
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', onPointerUp, { once: true });
      });
      resizeOverlay.appendChild(handleElement);
    };

    (['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as ResizeHandle[]).forEach(createHandle);

    return root;
  }

  ignoreEvent() {
    return true;
  }
}

class ImageLineNumberMarker extends GutterMarker {
  private readonly lineNumber: string;

  constructor(lineNumber: string) {
    super();
    this.lineNumber = lineNumber;
  }

  eq(other: ImageLineNumberMarker) {
    return other.lineNumber === this.lineNumber;
  }

  toDOM() {
    return document.createTextNode(this.lineNumber);
  }
}

const imageLineNumberMarker = lineNumberWidgetMarker.of((view, widget, block) => {
  if (!(widget instanceof MarkdownImageWidget)) {
    return null;
  }

  return new ImageLineNumberMarker(String(view.state.doc.lineAt(block.from).number));
});

const buildImageDecorations = (state: EditorState): DecorationSet => {
  const images = parseMarkdownImages(state.doc.toString());
  const selection = state.selection.main;
  const decorations = images.flatMap((image) => {
    const selectionInsideImage = selection.empty
      ? selection.from > image.from && selection.from < image.to
      : selection.from < image.to && selection.to > image.from;

    if (selectionInsideImage) {
      return [];
    }

    return Decoration.replace({
      widget: new MarkdownImageWidget(image, {
        preview: openImagePreview,
        edit: openImageEditor,
        resize: resizeImage,
        remove: removeImage,
      }),
      block: true,
    }).range(image.from, image.to);
  });

  return Decoration.set(decorations, true);
};

const imageWidgetField = StateField.define<DecorationSet>({
  create(state) {
    return buildImageDecorations(state);
  },
  update(value, transaction) {
    if (transaction.docChanged || transaction.selection) {
      return buildImageDecorations(transaction.state);
    }

    return value.map(transaction.changes);
  },
  provide: (field) => EditorView.decorations.from(field),
});

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

const guessFileExtension = (file: Blob & { name?: string }) => {
  const explicitName = file.name?.trim();
  if (explicitName && explicitName.includes('.')) {
    return explicitName.slice(explicitName.lastIndexOf('.'));
  }

  const mime = file.type.toLowerCase();
  if (mime === 'image/png') return '.png';
  if (mime === 'image/jpeg') return '.jpg';
  if (mime === 'image/webp') return '.webp';
  if (mime === 'image/gif') return '.gif';
  if (mime === 'image/svg+xml') return '.svg';
  if (mime === 'image/bmp') return '.bmp';
  return '';
};

const ensureUploadFileName = (file: File, fallbackBase: string) => {
  const trimmedName = file.name?.trim();
  if (trimmedName) {
    return file;
  }

  const extension = guessFileExtension(file);
  const safeName = `${fallbackBase}-${Date.now()}${extension}`;
  return new File([file], safeName, {
    type: file.type,
    lastModified: file.lastModified,
  });
};

const extractClipboardFiles = (event: ClipboardEvent) => {
  const directFiles = Array.from(event.clipboardData?.files ?? []);
  if (directFiles.length > 0) {
    return directFiles.map((file) => ensureUploadFileName(file, 'pasted-image'));
  }

  const items = Array.from(event.clipboardData?.items ?? []);
  return items
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => Boolean(file))
    .map((file) => ensureUploadFileName(file, 'pasted-image'));
};

const insertUploadedFiles = (entries: Array<[string, string]>) => {
  if (!editorView.value || entries.length === 0) return;

  const markdownText = entries
    .map(([name, url]) => {
      const isImage = /\.(png|jpe?g|gif|webp|svg|bmp)$/i.test(name);
      return isImage ? `![${name}](${url})` : `[${name}](${url})`;
    })
    .join('\n');

  const state = editorView.value.state;
  const { from, to } = state.selection.main;
  const charBefore = from > 0 ? state.doc.sliceString(from - 1, from) : '';
  const charAfter = to < state.doc.length ? state.doc.sliceString(to, to + 1) : '';
  const prefix = from === 0 || charBefore === '\n' ? '' : '\n';
  const suffix = to === state.doc.length || charAfter === '\n' ? '' : '\n';
  const insert = `${prefix}${markdownText}${suffix}`;
  const cursor = from + insert.length;

  insertRange(from, to, insert, cursor, cursor);
};

const uploadFiles = async (fileList: FileList | File[]) => {
  const files = Array.from(fileList).map((file) => ensureUploadFileName(file, 'uploaded-file'));
  if (files.length === 0) return;

  const formData = new FormData();
  files.forEach((file) => formData.append('file[]', file));

  try {
    const response = await api.post('/files/upload', formData);

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
    console.error('Failed to upload files', error);
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
  const files = extractClipboardFiles(event);
  if (files.length === 0) return;

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
    imageCursorKeymap,
    imageDeleteKeymap,
    imageLineNumberMarker,
    imageWidgetField,
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

// 每次预览 HTML 更新后，等待 DOM 刷新再渲染 Mermaid 图表
watch(renderedHtml, async () => {
  if (viewMode.value === 'edit') return; // 纯编辑模式不渲染预览
  await renderPreviewMermaid();
});

watch(viewMode, async (mode) => {
  if (mode !== 'split') {
    stopSplitResize();
  }

  await nextTick();
  syncSplitLayout();

  if (mode !== 'edit') {
    await renderMermaidBlocks(previewPaneRef.value);
  }

  if (mode === 'preview') return;
  editorView.value?.requestMeasure();
});

watch(splitIsDraggable, (enabled) => {
  if (!enabled) {
    stopSplitResize();
    splitEditorWidth.value = null;
    return;
  }

  syncSplitEditorWidth();
});

onMounted(async () => {
  try {
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

    await nextTick();
    syncSplitLayout();
    window.addEventListener('resize', handleWindowResize);

    if (typeof ResizeObserver !== 'undefined' && workspaceRef.value) {
      workspaceResizeObserver = new ResizeObserver(() => {
        syncSplitLayout();
        requestEditorLayout();
      });
      workspaceResizeObserver.observe(workspaceRef.value);
    }

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
  } catch (error) {
    console.error('Failed to initialize Markdown editor', error);
    syncingDocument.value = false;
    connectionState.value = props.collab ? 'offline' : 'local';
  }
});

onBeforeUnmount(() => {
  if (htmlEmitTimer) {
    clearTimeout(htmlEmitTimer);
  }

  stopSplitResize();
  window.removeEventListener('resize', handleWindowResize);
  workspaceResizeObserver?.disconnect();

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

    <div
      ref="workspaceRef"
      class="editor-workspace"
      :class="[
        `mode-${viewMode}`,
        {
          dragging: draggingFiles,
          resizing: resizingSplit,
          'split-draggable': splitIsDraggable,
          'split-stacked': splitShouldStack,
        },
      ]"
      :style="workspaceStyle"
    >
      <div v-show="viewMode !== 'preview'" class="editor-pane">
        <div ref="editorHostRef" class="editor-host"></div>
      </div>

      <button
        v-if="splitIsDraggable"
        type="button"
        class="split-divider"
        title="拖动调整编写栏和预览栏宽度"
        aria-label="拖动调整编写栏和预览栏宽度"
        @pointerdown="startSplitResize"
      >
        <span class="split-divider-handle"></span>
      </button>

      <div v-show="viewMode !== 'edit'" class="preview-pane" ref="previewPaneRef">
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

    <a-modal
      v-model:open="imagePreviewVisible"
      title="图片预览"
      :footer="null"
      width="960px"
      @cancel="closeImagePreview"
    >
      <div class="editor-image-preview-body">
        <img :src="imagePreviewSrc" :alt="imagePreviewAlt || '笔记图片预览'" />
      </div>
    </a-modal>

    <a-modal
      v-model:open="imageEditorVisible"
      title="图片设置"
      ok-text="应用"
      cancel-text="取消"
      @ok="applyImageEditor"
      @cancel="closeImageEditor"
    >
      <div v-if="imageEditorDraft" class="editor-image-form">
        <div class="editor-image-form-item">
          <span class="editor-image-form-label">描述</span>
          <a-input v-model:value="imageEditorDraft.alt" placeholder="这张图片想表达什么" />
        </div>

        <div class="editor-image-form-item">
          <span class="editor-image-form-label">地址</span>
          <a-input v-model:value="imageEditorDraft.url" placeholder="https://example.com/image.png" />
        </div>

        <div class="editor-image-form-item">
          <span class="editor-image-form-label">显示宽度</span>
          <div class="editor-image-size-row">
            <button
              v-for="preset in imageWidthPresets"
              :key="preset.label"
              type="button"
              class="editor-image-size-btn"
              :class="{ active: preset.width === imageEditorDraft.width && imageEditorDraft.height == null }"
              @click="
                setImageDraftWidth(preset.width);
                setImageDraftHeight(null);
              "
            >
              {{ preset.label }}
            </button>
          </div>
          <div class="editor-image-dimension-row">
            <a-input-number
              v-model:value="imageEditorDraft.width"
              class="editor-image-width-input"
              :min="80"
              :max="2400"
              :step="20"
              addon-before="宽"
              addon-after="px"
              placeholder="自动"
            />
            <a-input-number
              v-model:value="imageEditorDraft.height"
              class="editor-image-width-input"
              :min="80"
              :max="2400"
              :step="20"
              addon-before="高"
              addon-after="px"
              placeholder="自动"
            />
          </div>
          <div class="editor-image-form-hint">留空时按容器宽度自适应，适合正文大图。</div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.markdown-editor-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background:
    radial-gradient(circle at top right, rgba(0, 117, 222, 0.06), transparent 28%),
    linear-gradient(180deg, #fbfaf8 0%, #f6f5f4 100%);
  border-left: 1px solid rgba(0, 0, 0, 0.08);
}

.editor-toolbar,
.editor-statusbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(18px);
}

.editor-toolbar {
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.editor-statusbar {
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
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
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: rgba(255, 255, 255, 0.92);
  color: rgba(0, 0, 0, 0.95);
  border-radius: 8px;
  padding: 7px 12px;
  min-width: 42px;
  font-size: 13px;
  line-height: 1;
  box-shadow: var(--sn-shadow-card);
}

.toolbar-btn.active,
.toolbar-btn:hover {
  border-color: rgba(0, 117, 222, 0.2);
  background: #f2f9ff;
}

.editor-workspace {
  --split-divider-width: 18px;
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
  grid-template-columns: minmax(360px, 1.08fr) minmax(320px, 0.92fr);
}

.editor-workspace.mode-split.split-draggable {
  gap: 0;
  grid-template-columns: minmax(360px, 1.08fr) var(--split-divider-width) minmax(320px, 0.92fr);
}

.editor-workspace.mode-split.split-stacked {
  grid-template-columns: 1fr;
}

.editor-pane,
.preview-pane {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--sn-shadow-card);
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.split-divider {
  position: relative;
  align-self: stretch;
  width: var(--split-divider-width);
  min-width: var(--split-divider-width);
  padding: 0;
  border: none;
  background: transparent;
  cursor: col-resize;
  touch-action: none;
  user-select: none;
}

.split-divider::before {
  content: '';
  position: absolute;
  top: 20px;
  bottom: 20px;
  left: calc(50% - 1px);
  width: 2px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0), rgba(0, 0, 0, 0.16), rgba(0, 0, 0, 0));
  transition: opacity 0.2s ease, transform 0.2s ease;
  opacity: 0.65;
}

.split-divider-handle {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 6px;
  height: 92px;
  border-radius: 999px;
  transform: translate(-50%, -50%);
  background: linear-gradient(180deg, rgba(0, 117, 222, 0.18), rgba(0, 0, 0, 0.1), rgba(0, 117, 222, 0.18));
  box-shadow: 0 14px 30px rgba(0, 0, 0, 0.08);
  transition: transform 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}

.split-divider:hover::before,
.editor-workspace.resizing .split-divider::before {
  opacity: 1;
  transform: scaleX(1.5);
}

.split-divider:hover .split-divider-handle,
.editor-workspace.resizing .split-divider-handle {
  transform: translate(-50%, -50%) scaleX(1.24);
  background: linear-gradient(180deg, rgba(0, 117, 222, 0.3), rgba(0, 0, 0, 0.18), rgba(0, 117, 222, 0.3));
  box-shadow: 0 18px 38px rgba(0, 0, 0, 0.12);
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
  color: rgba(0, 0, 0, 0.95);
  line-height: 1.75;
}

.markdown-preview :deep(h1),
.markdown-preview :deep(h2),
.markdown-preview :deep(h3) {
  line-height: 1.25;
  color: rgba(0, 0, 0, 0.95);
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
  background: rgba(0, 0, 0, 0.05);
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
}

.markdown-preview :deep(pre) {
  padding: 1rem 1.1rem;
  overflow: auto;
  background: #f6f5f4;
  color: rgba(0, 0, 0, 0.95);
  border-radius: 1rem;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.markdown-preview :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.markdown-preview :deep(img),
.markdown-preview :deep(.markdown-rendered-image) {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 1.15rem auto;
  border-radius: 18px;
  box-shadow: var(--sn-shadow-card);
}

.markdown-preview :deep(blockquote) {
  margin-left: 0;
  padding: 0.1rem 0 0.1rem 1rem;
  border-left: 4px solid #0075de;
  color: #615d59;
  background: #f7fbff;
}

.markdown-preview :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 1rem;
}

.markdown-preview :deep(th),
.markdown-preview :deep(td) {
  border: 1px solid rgba(0, 0, 0, 0.1);
  padding: 0.65rem 0.85rem;
}

.preview-empty {
  color: #a39e98;
  text-align: center;
  padding: 3rem 0;
}

/* Mermaid 图表容器 */
.markdown-preview :deep(.mermaid-block) {
  margin: 1.5rem 0;
  overflow-x: auto;
  text-align: center;
}

/* Mermaid 语法错误提示 */
.markdown-preview :deep(.mermaid-error) {
  color: #dc2626;
  background: rgba(220, 38, 38, 0.06);
  border: 1px solid rgba(220, 38, 38, 0.2);
  border-radius: 0.5rem;
  padding: 0.75rem 1rem;
  font-size: 13px;
  white-space: pre-wrap;
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
  color: #615d59;
  background: rgba(0, 0, 0, 0.05);
}

.status-badge.connecting {
  color: #dd5b00;
  background: rgba(221, 91, 0, 0.12);
}

.status-badge.live {
  color: #1aae39;
  background: rgba(26, 174, 57, 0.12);
}

.status-badge.offline {
  color: #d4380d;
  background: rgba(212, 56, 13, 0.12);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
}

.status-hint {
  font-size: 12px;
  color: #615d59;
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
  background: rgba(0, 117, 222, 0.08);
  border: 2px dashed rgba(0, 117, 222, 0.28);
}

.drag-panel {
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.94);
  padding: 18px 26px;
  font-weight: 700;
  color: #0075de;
}

.sync-mask {
  flex-direction: column;
  gap: 12px;
  background: rgba(246, 245, 244, 0.92);
  color: rgba(0, 0, 0, 0.95);
  font-weight: 600;
}

.hidden-file-input {
  display: none;
}

.editor-image-preview-body {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
  max-height: 72vh;
  overflow: auto;
  border-radius: 20px;
  background:
    radial-gradient(circle at top right, rgba(0, 117, 222, 0.08), transparent 28%),
    linear-gradient(180deg, #fbfaf8 0%, #f6f5f4 100%);
  padding: 18px;
}

.editor-image-preview-body img {
  display: block;
  max-width: 100%;
  max-height: calc(72vh - 36px);
  object-fit: contain;
  border-radius: 18px;
  box-shadow: var(--sn-shadow-deep);
  background: rgba(255, 255, 255, 0.94);
}

.editor-image-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.editor-image-form-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.editor-image-form-label {
  color: rgba(0, 0, 0, 0.95);
  font-size: 13px;
  font-weight: 700;
}

.editor-image-size-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.editor-image-dimension-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.editor-image-size-btn {
  border: 1px solid rgba(148, 163, 184, 0.24);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  color: #334155;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  padding: 8px 12px;
}

.editor-image-size-btn.active {
  border-color: #0075de;
  background: #f2f9ff;
  color: #0075de;
}

.editor-image-width-input {
  width: 220px;
}

.editor-image-form-hint {
  color: #615d59;
  font-size: 12px;
}

@media (max-width: 1100px) {
  .editor-workspace.mode-split,
  .editor-workspace.mode-split.split-draggable {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .split-divider {
    display: none;
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

  .editor-image-width-input {
    width: 100%;
  }
}
</style>
