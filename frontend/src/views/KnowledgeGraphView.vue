<script setup lang="ts">
import {
  AimOutlined,
  ApartmentOutlined,
  ArrowLeftOutlined,
  BookOutlined,
  FileTextOutlined,
  PushpinOutlined,
  ReloadOutlined,
  TagOutlined,
} from '@ant-design/icons-vue';
import { message } from 'ant-design-vue';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import api from '../api';

type GraphNodeType = 'notebook' | 'note' | 'tag';
type GraphLinkType = 'contains' | 'tagged' | 'related';

type GraphSummary = {
  notebookCount: number;
  noteCount: number;
  tagCount: number;
  relationCount: number;
  relatedNoteCount: number;
};

type GraphNode = {
  id: string;
  label: string;
  type: GraphNodeType;
  size: number;
  description: string;
  path: string | null;
  entityId: number | null;
  notebookName: string | null;
  updatedAt: string | null;
};

type GraphLink = {
  source: string;
  target: string;
  type: GraphLinkType;
  weight: number;
  label: string;
};

type RelationFilterType = 'all' | GraphLinkType;

type SelectedRelation = GraphLink & {
  otherNode: GraphNode | undefined;
};

type SelectedRelationGroup = {
  type: GraphLinkType;
  links: SelectedRelation[];
};

type GraphResponse = {
  summary: GraphSummary;
  nodes: GraphNode[];
  links: GraphLink[];
};

type RenderNode = GraphNode & {
  x: number;
  y: number;
  vx: number;
  vy: number;
  pinned: boolean;
};

type RenderLink = GraphLink & {
  sourceNode: RenderNode;
  targetNode: RenderNode;
};

type DragState = {
  nodeId: string;
  pointerId: number;
  offsetX: number;
  offsetY: number;
  moved: boolean;
  lastX: number;
  lastY: number;
};

const TYPE_META: Record<GraphNodeType, { label: string; color: string; soft: string; anchor: [number, number] }> = {
  notebook: { label: '笔记本', color: '#b7791f', soft: 'rgba(245, 158, 11, 0.14)', anchor: [0.2, 0.28] },
  note: { label: '笔记', color: '#2563eb', soft: 'rgba(37, 99, 235, 0.12)', anchor: [0.5, 0.44] },
  tag: { label: '标签', color: '#0f766e', soft: 'rgba(15, 118, 110, 0.14)', anchor: [0.8, 0.7] },
};

const LINK_META: Record<GraphLinkType, { label: string; color: string; accent: string; soft: string; dash?: string }> = {
  contains: { label: '包含关系', color: 'rgba(37, 99, 235, 0.26)', accent: '#2563eb', soft: 'rgba(37, 99, 235, 0.1)' },
  tagged: { label: '标签关系', color: 'rgba(15, 118, 110, 0.26)', accent: '#0f766e', soft: 'rgba(15, 118, 110, 0.11)' },
  related: { label: '主题关联', color: 'rgba(217, 119, 6, 0.34)', accent: '#d97706', soft: 'rgba(217, 119, 6, 0.12)', dash: '6 6' },
};

const RELATION_TYPE_ORDER: GraphLinkType[] = ['contains', 'tagged', 'related'];

const router = useRouter();
const graphHostRef = ref<HTMLElement | null>(null);
const svgRef = ref<SVGSVGElement | null>(null);
const loading = ref(true);
const refreshing = ref(false);
const graph = ref<GraphResponse | null>(null);
const selectedNodeId = ref<string | null>(null);
const query = ref('');
const showRelatedLinks = ref(true);
const activeRelationFilter = ref<RelationFilterType>('all');
const viewport = reactive({
  width: 1120,
  height: 700,
});
const typeFilters = reactive<Record<GraphNodeType, boolean>>({
  notebook: true,
  note: true,
  tag: true,
});
const renderedNodes = ref<RenderNode[]>([]);
const renderedLinks = ref<RenderLink[]>([]);
const dragState = ref<DragState | null>(null);
const suppressNodeClickUntil = ref(0);
const pinnedNodeIds = ref<Set<string>>(new Set());

let resizeObserver: ResizeObserver | null = null;
let reboundAnimationFrame: number | null = null;
const VIEWPORT_EPSILON = 2;
const DRAG_THRESHOLD = 6;
const GRAPH_PADDING = 44;
const REBOUND_DURATION_MS = 260;

const summary = computed<GraphSummary>(() => graph.value?.summary ?? {
  notebookCount: 0,
  noteCount: 0,
  tagCount: 0,
  relationCount: 0,
  relatedNoteCount: 0,
});

const allowedLinks = computed(() => {
  if (!graph.value) {
    return [];
  }

  return graph.value.links.filter((link) => {
    if (!showRelatedLinks.value && link.type === 'related') {
      return false;
    }

    return true;
  });
});

const adjacencyMap = computed(() => {
  const adjacency = new Map<string, Set<string>>();

  for (const link of allowedLinks.value) {
    if (!adjacency.has(link.source)) {
      adjacency.set(link.source, new Set());
    }
    if (!adjacency.has(link.target)) {
      adjacency.set(link.target, new Set());
    }
    adjacency.get(link.source)!.add(link.target);
    adjacency.get(link.target)!.add(link.source);
  }

  return adjacency;
});

const filteredGraph = computed(() => {
  if (!graph.value) {
    return {
      nodes: [] as GraphNode[],
      links: [] as GraphLink[],
    };
  }

  const baseNodes = graph.value.nodes.filter((node) => typeFilters[node.type]);
  const normalizedQuery = query.value.trim().toLowerCase();
  const baseNodeIds = new Set(baseNodes.map((node) => node.id));
  let visibleIds = new Set(baseNodeIds);

  if (normalizedQuery) {
    const matchedIds = new Set<string>();

    for (const node of baseNodes) {
      const haystack = [node.label, node.description, node.notebookName, node.updatedAt]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();

      if (haystack.includes(normalizedQuery)) {
        matchedIds.add(node.id);
        adjacencyMap.value.get(node.id)?.forEach((neighborId) => {
          if (baseNodeIds.has(neighborId)) {
            matchedIds.add(neighborId);
          }
        });
      }
    }

    visibleIds = matchedIds;
  }

  const links = allowedLinks.value.filter((link) => visibleIds.has(link.source) && visibleIds.has(link.target));
  const connectedIds = new Set<string>();
  for (const link of links) {
    connectedIds.add(link.source);
    connectedIds.add(link.target);
  }

  const nodes = baseNodes.filter((node) => {
    if (visibleIds.has(node.id) && query.value.trim()) {
      return true;
    }
    if (connectedIds.has(node.id)) {
      return true;
    }
    return visibleIds.has(node.id);
  });

  return { nodes, links };
});

const activeNodeIds = computed(() => {
  const ids = new Set<string>();

  if (!selectedNodeId.value) {
    return ids;
  }

  ids.add(selectedNodeId.value);
  for (const link of renderedLinks.value) {
    if (link.source === selectedNodeId.value) {
      ids.add(link.target);
    }
    if (link.target === selectedNodeId.value) {
      ids.add(link.source);
    }
  }

  return ids;
});

const selectedNode = computed(() => (
  renderedNodes.value.find((node) => node.id === selectedNodeId.value)
  ?? filteredGraph.value.nodes.find((node) => node.id === selectedNodeId.value)
  ?? null
));

const selectedNodePinned = computed(() => (
  selectedNode.value ? pinnedNodeIds.value.has(selectedNode.value.id) : false
));

const selectedLinks = computed(() => {
  if (!selectedNode.value) {
    return [] as SelectedRelation[];
  }

  const nodeMap = new Map(filteredGraph.value.nodes.map((node) => [node.id, node]));

  return filteredGraph.value.links
    .filter((link) => link.source === selectedNode.value!.id || link.target === selectedNode.value!.id)
    .map((link) => ({
      ...link,
      otherNode: nodeMap.get(link.source === selectedNode.value!.id ? link.target : link.source),
    }));
});

const selectedMetrics = computed(() => {
  const metrics = {
    contains: 0,
    tagged: 0,
    related: 0,
  };

  for (const link of selectedLinks.value) {
    metrics[link.type] += 1;
  }

  return metrics;
});

const relationFilterOptions = computed(() => ([
  {
    key: 'all' as RelationFilterType,
    label: '全部',
    count: selectedLinks.value.length,
    accent: '#475569',
    soft: 'rgba(226, 232, 240, 0.55)',
  },
  ...RELATION_TYPE_ORDER.map((type) => ({
    key: type as RelationFilterType,
    label: LINK_META[type].label,
    count: selectedMetrics.value[type],
    accent: LINK_META[type].accent,
    soft: LINK_META[type].soft,
  })),
]));

const selectedLinkGroups = computed<SelectedRelationGroup[]>(() => (
  RELATION_TYPE_ORDER
    .map((type) => ({
      type,
      links: selectedLinks.value
        .filter((link) => link.type === type)
        .sort((left, right) => {
          const leftLabel = left.otherNode?.label ?? left.label ?? '';
          const rightLabel = right.otherNode?.label ?? right.label ?? '';
          return leftLabel.localeCompare(rightLabel, 'zh-Hans-CN');
        }),
    }))
    .filter((group) => group.links.length > 0)
));

const visibleSelectedLinkGroups = computed<SelectedRelationGroup[]>(() => {
  if (activeRelationFilter.value === 'all') {
    return selectedLinkGroups.value;
  }

  return selectedLinkGroups.value.filter((group) => group.type === activeRelationFilter.value);
});

const loadGraph = async (silent = false) => {
  if (silent) {
    refreshing.value = true;
  } else {
    loading.value = true;
  }

  try {
    const response = await api.get<GraphResponse>('/knowledge-graph');
    graph.value = response.data;
    const firstNode = response.data.nodes[0];
    if (!selectedNodeId.value && firstNode) {
      selectedNodeId.value = response.data.nodes.find((node) => node.type === 'note')?.id ?? firstNode.id;
    }
    await nextTick();
    updateViewport();
    rebuildLayout();
  } catch (error) {
    message.error('知识图谱加载失败');
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
};

const updateViewport = () => {
  const host = graphHostRef.value;
  if (!host) {
    return;
  }

  const nextWidth = Math.max(host.clientWidth, 720);
  const nextHeight = Math.max(host.clientHeight, 520);

  if (Math.abs(viewport.width - nextWidth) > VIEWPORT_EPSILON) {
    viewport.width = nextWidth;
  }

  if (Math.abs(viewport.height - nextHeight) > VIEWPORT_EPSILON) {
    viewport.height = nextHeight;
  }
};

const getAnchor = (type: GraphNodeType) => ({
  x: viewport.width * TYPE_META[type].anchor[0],
  y: viewport.height * TYPE_META[type].anchor[1],
});

const hashString = (value: string) => {
  let hash = 0;

  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }

  return hash;
};

const clamp = (value: number, min: number, max: number) => (
  Math.min(max, Math.max(min, value))
);

const isNodePinned = (nodeId: string) => pinnedNodeIds.value.has(nodeId);

const commitGraphMutation = () => {
  renderedNodes.value = [...renderedNodes.value];
  renderedLinks.value = [...renderedLinks.value];
};

const stopReboundAnimation = () => {
  if (reboundAnimationFrame !== null) {
    cancelAnimationFrame(reboundAnimationFrame);
    reboundAnimationFrame = null;
  }
};

const toSvgPoint = (event: PointerEvent) => {
  const svg = svgRef.value;
  if (!svg) {
    return null;
  }

  const rect = svg.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) {
    return null;
  }

  return {
    x: ((event.clientX - rect.left) / rect.width) * viewport.width,
    y: ((event.clientY - rect.top) / rect.height) * viewport.height,
  };
};

const buildSeedNode = (node: GraphNode, index: number, total: number): RenderNode => {
  const anchor = getAnchor(node.type);
  const hash = hashString(node.id);
  const angle = ((hash % 360) / 180) * Math.PI;
  const ringIndex = total === 0 ? 0 : index % Math.max(Math.ceil(total / 3), 1);
  const radiusBase = node.type === 'note' ? 92 : node.type === 'notebook' ? 76 : 82;
  const radius = radiusBase + ringIndex * 12;

  return {
    ...node,
    x: anchor.x + Math.cos(angle) * radius,
    y: anchor.y + Math.sin(angle) * radius,
    vx: 0,
    vy: 0,
    pinned: isNodePinned(node.id),
  };
};

const buildLayout = () => {
  const currentNodeMap = new Map(renderedNodes.value.map((node) => [node.id, node]));
  const nextNodes = filteredGraph.value.nodes.map((node, index, allNodes) => buildSeedNode(node, index, allNodes.length));

  for (const node of nextNodes) {
    if (!node.pinned) {
      continue;
    }

    const currentNode = currentNodeMap.get(node.id);
    if (!currentNode) {
      continue;
    }

    node.x = currentNode.x;
    node.y = currentNode.y;
  }

  const nodeMap = new Map(nextNodes.map((node) => [node.id, node]));
  const nextLinks = filteredGraph.value.links
    .map((link) => {
      const sourceNode = nodeMap.get(link.source);
      const targetNode = nodeMap.get(link.target);

      if (!sourceNode || !targetNode) {
        return null;
      }

      return {
        ...link,
        sourceNode,
        targetNode,
      };
    })
    .filter((link): link is RenderLink => Boolean(link));

  simulateLayout(nextNodes, nextLinks);
  return {
    nextNodes,
    nextLinks,
    nodeMap,
  };
};

const rebuildLayout = () => {
  const { nextNodes, nextLinks, nodeMap } = buildLayout();
  renderedNodes.value = nextNodes;
  renderedLinks.value = nextLinks;

  if (selectedNodeId.value && !nodeMap.has(selectedNodeId.value)) {
    selectedNodeId.value = nextNodes[0]?.id ?? null;
  }
};

const simulateLayout = (nodes: RenderNode[], links: RenderLink[]) => {
  if (nodes.length === 0) {
    return;
  }

  const iterations = Math.max(80, Math.min(220, 220 - nodes.length));
  const repulsion = nodes.length > 80 ? 3800 : 7600;

  for (let iteration = 0; iteration < iterations; iteration += 1) {
    for (let left = 0; left < nodes.length; left += 1) {
      for (let right = left + 1; right < nodes.length; right += 1) {
        const source = nodes[left];
        const target = nodes[right];
        if (!source || !target) {
          continue;
        }
        let dx = target.x - source.x;
        let dy = target.y - source.y;
        let distanceSquared = dx * dx + dy * dy;

        if (distanceSquared < 1) {
          dx = 1;
          dy = 0.5;
          distanceSquared = 1;
        }

        const distance = Math.sqrt(distanceSquared);
        const force = repulsion / distanceSquared;
        const forceX = (dx / distance) * force;
        const forceY = (dy / distance) * force;

        if (!source.pinned) {
          source.vx -= forceX;
          source.vy -= forceY;
        }
        if (!target.pinned) {
          target.vx += forceX;
          target.vy += forceY;
        }
      }
    }

    for (const link of links) {
      const dx = link.targetNode.x - link.sourceNode.x;
      const dy = link.targetNode.y - link.sourceNode.y;
      const distance = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
      const desiredDistance = link.type === 'related' ? 196 : link.type === 'contains' ? 168 : 144;
      const strength = link.type === 'related' ? 0.008 * Math.max(link.weight, 1) : 0.018;
      const springForce = (distance - desiredDistance) * strength;
      const forceX = (dx / distance) * springForce;
      const forceY = (dy / distance) * springForce;

      if (!link.sourceNode.pinned) {
        link.sourceNode.vx += forceX;
        link.sourceNode.vy += forceY;
      }
      if (!link.targetNode.pinned) {
        link.targetNode.vx -= forceX;
        link.targetNode.vy -= forceY;
      }
    }

    for (const node of nodes) {
      if (node.pinned) {
        node.vx = 0;
        node.vy = 0;
        continue;
      }

      const anchor = getAnchor(node.type);
      node.vx += (anchor.x - node.x) * 0.003;
      node.vy += (anchor.y - node.y) * 0.003;
      node.vx *= 0.82;
      node.vy *= 0.82;
      node.x = clamp(node.x + node.vx, GRAPH_PADDING, viewport.width - GRAPH_PADDING);
      node.y = clamp(node.y + node.vy, GRAPH_PADDING, viewport.height - GRAPH_PADDING);
    }
  }
};

const selectNode = (nodeId: string) => {
  selectedNodeId.value = nodeId;
};

const togglePinSelectedNode = () => {
  if (!selectedNode.value) {
    return;
  }

  const nextPinnedNodeIds = new Set(pinnedNodeIds.value);
  if (nextPinnedNodeIds.has(selectedNode.value.id)) {
    nextPinnedNodeIds.delete(selectedNode.value.id);
  } else {
    nextPinnedNodeIds.add(selectedNode.value.id);
  }
  pinnedNodeIds.value = nextPinnedNodeIds;
  rebuildLayout();
};

const resetLayout = () => {
  stopReboundAnimation();
  pinnedNodeIds.value = new Set();
  rebuildLayout();
};

const startReboundAnimation = () => {
  stopReboundAnimation();

  const { nextNodes, nextLinks } = buildLayout();
  if (renderedNodes.value.length !== nextNodes.length) {
    renderedNodes.value = nextNodes;
    renderedLinks.value = nextLinks;
    return;
  }

  const targetNodeMap = new Map(nextNodes.map((node) => [node.id, node]));
  const startPositions = new Map(renderedNodes.value.map((node) => [node.id, { x: node.x, y: node.y }]));
  const startTime = performance.now();

  const tick = (timestamp: number) => {
    const progress = Math.min(1, (timestamp - startTime) / REBOUND_DURATION_MS);
    const eased = 1 - ((1 - progress) ** 3);

    for (const node of renderedNodes.value) {
      const start = startPositions.get(node.id);
      const target = targetNodeMap.get(node.id);
      if (!start || !target) {
        continue;
      }

      node.x = start.x + ((target.x - start.x) * eased);
      node.y = start.y + ((target.y - start.y) * eased);
      node.vx = 0;
      node.vy = 0;
    }

    commitGraphMutation();

    if (progress < 1) {
      reboundAnimationFrame = requestAnimationFrame(tick);
      return;
    }

    reboundAnimationFrame = null;
    renderedNodes.value = nextNodes;
    renderedLinks.value = nextLinks;
  };

  reboundAnimationFrame = requestAnimationFrame(tick);
};

const handleNodeClick = (nodeId: string) => {
  if (Date.now() < suppressNodeClickUntil.value) {
    return;
  }

  selectNode(nodeId);
};

const handleNodeOpen = (path: string | null) => {
  if (!path || Date.now() < suppressNodeClickUntil.value) {
    return;
  }

  router.push(path);
};

const handleNodePointerDown = (node: RenderNode, event: PointerEvent) => {
  const point = toSvgPoint(event);
  if (!point) {
    return;
  }

  stopReboundAnimation();
  dragState.value = {
    nodeId: node.id,
    pointerId: event.pointerId,
    offsetX: node.x - point.x,
    offsetY: node.y - point.y,
    moved: false,
    lastX: node.x,
    lastY: node.y,
  };
  selectNode(node.id);
};

const applyNeighborInfluence = (nodeId: string, deltaX: number, deltaY: number) => {
  if (deltaX === 0 && deltaY === 0) {
    return;
  }

  const nodeMap = new Map(renderedNodes.value.map((node) => [node.id, node]));

  for (const link of renderedLinks.value) {
    if (link.source !== nodeId && link.target !== nodeId) {
      continue;
    }

    const otherNodeId = link.source === nodeId ? link.target : link.source;
    const otherNode = nodeMap.get(otherNodeId);

    if (!otherNode || otherNode.pinned) {
      continue;
    }

    const influence = link.type === 'related'
      ? 0.14 + Math.min(link.weight, 4) * 0.025
      : link.type === 'contains'
        ? 0.22
        : 0.18;

    otherNode.x = clamp(otherNode.x + (deltaX * influence), GRAPH_PADDING, viewport.width - GRAPH_PADDING);
    otherNode.y = clamp(otherNode.y + (deltaY * influence), GRAPH_PADDING, viewport.height - GRAPH_PADDING);
    otherNode.vx = 0;
    otherNode.vy = 0;
  }
};

const handlePointerMove = (event: PointerEvent) => {
  const currentDragState = dragState.value;
  if (!currentDragState || currentDragState.pointerId !== event.pointerId) {
    return;
  }

  const point = toSvgPoint(event);
  if (!point) {
    return;
  }

  const node = renderedNodes.value.find((item) => item.id === currentDragState.nodeId);
  if (!node) {
    dragState.value = null;
    return;
  }

  const nextX = clamp(point.x + currentDragState.offsetX, GRAPH_PADDING, viewport.width - GRAPH_PADDING);
  const nextY = clamp(point.y + currentDragState.offsetY, GRAPH_PADDING, viewport.height - GRAPH_PADDING);
  const deltaX = nextX - currentDragState.lastX;
  const deltaY = nextY - currentDragState.lastY;

  if (!currentDragState.moved) {
    const distance = Math.hypot(nextX - node.x, nextY - node.y);
    if (distance >= DRAG_THRESHOLD) {
      currentDragState.moved = true;
    }
  }

  node.x = nextX;
  node.y = nextY;
  node.vx = 0;
  node.vy = 0;
  currentDragState.lastX = nextX;
  currentDragState.lastY = nextY;
  applyNeighborInfluence(node.id, deltaX, deltaY);
  commitGraphMutation();
};

const endDrag = (pointerId: number) => {
  const currentDragState = dragState.value;
  if (!currentDragState || currentDragState.pointerId !== pointerId) {
    return;
  }

  const shouldSuppressClick = currentDragState.moved;
  dragState.value = null;

  if (shouldSuppressClick) {
    suppressNodeClickUntil.value = Date.now() + 180;
  }

  startReboundAnimation();
};

const handlePointerUp = (event: PointerEvent) => {
  endDrag(event.pointerId);
};

const handlePointerCancel = (event: PointerEvent) => {
  endDrag(event.pointerId);
};

const openSelectedNode = () => {
  if (selectedNode.value?.path) {
    router.push(selectedNode.value.path);
  }
};

const isLinkDimmed = (link: RenderLink) => {
  if (!selectedNodeId.value) {
    return false;
  }

  return link.source !== selectedNodeId.value && link.target !== selectedNodeId.value;
};

const nodeOpacity = (node: RenderNode) => {
  if (!selectedNodeId.value) {
    return 1;
  }

  return activeNodeIds.value.has(node.id) ? 1 : 0.28;
};

watch(
  () => filteredGraph.value,
  () => {
    rebuildLayout();
  },
  { deep: true },
);

watch(
  () => [viewport.width, viewport.height],
  () => {
    rebuildLayout();
  },
);

onMounted(async () => {
  await nextTick();
  updateViewport();

  if (graphHostRef.value) {
    resizeObserver = new ResizeObserver(() => {
      updateViewport();
    });
    resizeObserver.observe(graphHostRef.value);
  }

  window.addEventListener('pointermove', handlePointerMove);
  window.addEventListener('pointerup', handlePointerUp);
  window.addEventListener('pointercancel', handlePointerCancel);

  await loadGraph();
});

onBeforeUnmount(() => {
  stopReboundAnimation();
  resizeObserver?.disconnect();
  window.removeEventListener('pointermove', handlePointerMove);
  window.removeEventListener('pointerup', handlePointerUp);
  window.removeEventListener('pointercancel', handlePointerCancel);
});
</script>

<template>
  <div class="graph-page">
    <div class="graph-header">
      <div class="header-main">
        <a-button type="text" class="back-btn" @click="router.push('/home')">
          <template #icon><ArrowLeftOutlined /></template>
        </a-button>

        <div>
          <div class="eyebrow">Knowledge Graph</div>
          <h1>知识图谱可视化</h1>
          <p>把笔记本、笔记和标签放进同一张关系图里，快速看清结构、热点主题和关联内容。</p>
        </div>
      </div>

      <a-button type="primary" shape="round" :loading="refreshing" @click="loadGraph(true)">
        <template #icon><ReloadOutlined /></template>
        刷新图谱
      </a-button>
    </div>

    <div class="summary-grid">
      <div class="summary-card notebook-card">
        <span class="summary-icon"><BookOutlined /></span>
        <div>
          <div class="summary-value">{{ summary.notebookCount }}</div>
          <div class="summary-label">笔记本节点</div>
        </div>
      </div>

      <div class="summary-card note-card">
        <span class="summary-icon"><FileTextOutlined /></span>
        <div>
          <div class="summary-value">{{ summary.noteCount }}</div>
          <div class="summary-label">笔记节点</div>
        </div>
      </div>

      <div class="summary-card tag-card">
        <span class="summary-icon"><TagOutlined /></span>
        <div>
          <div class="summary-value">{{ summary.tagCount }}</div>
          <div class="summary-label">标签节点</div>
        </div>
      </div>

      <div class="summary-card relation-card">
        <span class="summary-icon"><ApartmentOutlined /></span>
        <div>
          <div class="summary-value">{{ summary.relationCount }}</div>
          <div class="summary-label">关系连线</div>
        </div>
      </div>
    </div>

    <div class="control-bar">
      <a-input-search
        v-model:value="query"
        class="search-input"
        placeholder="搜索节点名称、描述或所属笔记本"
        allow-clear
      />

      <div class="toggle-group">
        <button
          type="button"
          class="type-toggle"
          :class="{ active: typeFilters.notebook }"
          @click="typeFilters.notebook = !typeFilters.notebook"
        >
          笔记本
        </button>
        <button
          type="button"
          class="type-toggle"
          :class="{ active: typeFilters.note }"
          @click="typeFilters.note = !typeFilters.note"
        >
          笔记
        </button>
        <button
          type="button"
          class="type-toggle"
          :class="{ active: typeFilters.tag }"
          @click="typeFilters.tag = !typeFilters.tag"
        >
          标签
        </button>
      </div>

      <div class="switch-wrap">
        <span>显示主题关联</span>
        <a-switch v-model:checked="showRelatedLinks" />
      </div>

      <a-button @click="resetLayout">
        <template #icon><AimOutlined /></template>
        重置布局
      </a-button>
    </div>

    <div class="workspace">
      <section class="graph-stage">
        <div class="stage-head">
          <div>
            <h2>关系网络</h2>
            <p>点击节点查看详情，双击笔记或笔记本节点可直接跳转。</p>
          </div>

          <div class="legend">
            <span class="legend-item">
              <i class="legend-dot notebook"></i>
              笔记本
            </span>
            <span class="legend-item">
              <i class="legend-dot note"></i>
              笔记
            </span>
            <span class="legend-item">
              <i class="legend-dot tag"></i>
              标签
            </span>
            <span class="legend-item">
              <i class="legend-line related"></i>
              共同标签
            </span>
          </div>
        </div>

        <div ref="graphHostRef" class="graph-host" :class="{ dragging: Boolean(dragState) }">
          <div v-if="loading" class="state-panel">
            <a-spin size="large" />
            <span>正在生成知识图谱...</span>
          </div>

          <div v-else-if="renderedNodes.length === 0" class="state-panel">
            <a-empty description="当前筛选条件下没有可展示的图谱节点" />
          </div>

          <svg
            v-else
            ref="svgRef"
            class="graph-svg"
            :viewBox="`0 0 ${viewport.width} ${viewport.height}`"
            preserveAspectRatio="xMidYMid meet"
          >
            <g class="link-layer">
              <line
                v-for="link in renderedLinks"
                :key="`${link.type}-${link.source}-${link.target}`"
                class="graph-link"
                :class="[link.type, { dimmed: isLinkDimmed(link) }]"
                :x1="link.sourceNode.x"
                :y1="link.sourceNode.y"
                :x2="link.targetNode.x"
                :y2="link.targetNode.y"
                :stroke="LINK_META[link.type].color"
                :stroke-dasharray="LINK_META[link.type].dash"
                :stroke-width="link.type === 'related' ? Math.min(1.4 + link.weight * 0.6, 4.4) : 1.6"
              />
            </g>

            <g class="node-layer">
              <g
                v-for="node in renderedNodes"
                :key="node.id"
                class="graph-node"
                :class="[node.type, { selected: node.id === selectedNodeId, dragging: dragState?.nodeId === node.id }]"
                :style="{ opacity: nodeOpacity(node) }"
                :transform="`translate(${node.x}, ${node.y})`"
                @pointerdown.stop.prevent="handleNodePointerDown(node, $event)"
                @click="handleNodeClick(node.id)"
                @dblclick="handleNodeOpen(node.path)"
              >
                <circle
                  class="node-halo"
                  :r="node.size + 7"
                  :fill="TYPE_META[node.type].soft"
                />
                <circle
                  class="node-core"
                  :r="node.size"
                  :fill="TYPE_META[node.type].color"
                />
                <circle
                  v-if="node.id === selectedNodeId"
                  class="node-ring"
                  :r="node.size + 4"
                />
                <text
                  class="node-label"
                  :y="node.size + 18"
                  text-anchor="middle"
                >
                  {{ node.label.length > 14 ? `${node.label.slice(0, 14)}…` : node.label }}
                </text>
              </g>
            </g>
          </svg>
        </div>
      </section>

      <aside class="detail-panel">
        <template v-if="selectedNode">
          <div class="detail-head">
            <a-tag :color="selectedNode.type === 'notebook' ? 'gold' : selectedNode.type === 'note' ? 'blue' : 'cyan'">
              {{ TYPE_META[selectedNode.type].label }}
            </a-tag>
            <h3>{{ selectedNode.label }}</h3>
            <p>{{ selectedNode.description || '暂无说明' }}</p>
          </div>

          <div class="detail-grid">
            <div class="detail-metric">
              <span>包含关系</span>
              <strong>{{ selectedMetrics.contains }}</strong>
            </div>
            <div class="detail-metric">
              <span>标签关系</span>
              <strong>{{ selectedMetrics.tagged }}</strong>
            </div>
            <div class="detail-metric">
              <span>主题关联</span>
              <strong>{{ selectedMetrics.related }}</strong>
            </div>
          </div>

          <div class="detail-meta">
            <div v-if="selectedNode.notebookName" class="meta-row">
              <span class="meta-label">所属笔记本</span>
              <span>{{ selectedNode.notebookName }}</span>
            </div>
            <div v-if="selectedNode.updatedAt" class="meta-row">
              <span class="meta-label">最近更新</span>
              <span>{{ selectedNode.updatedAt }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">可见关系数</span>
              <span>{{ selectedLinks.length }}</span>
            </div>
          </div>

          <div class="detail-actions">
            <a-button block @click="togglePinSelectedNode">
              <template #icon><PushpinOutlined /></template>
              {{ selectedNodePinned ? '取消固定节点' : '固定节点位置' }}
            </a-button>

            <a-button v-if="selectedNode.path" type="primary" block @click="openSelectedNode">
              打开对应内容
            </a-button>
          </div>

          <div class="related-list">
            <div class="related-head">
              <div class="related-title">关联节点</div>
              <span class="related-total">{{ selectedLinks.length }}</span>
            </div>

            <div class="relation-filter-bar">
              <button
                v-for="option in relationFilterOptions"
                :key="option.key"
                type="button"
                class="relation-filter"
                :class="{ 'is-active': activeRelationFilter === option.key }"
                :style="{ '--filter-accent': option.accent, '--filter-soft': option.soft }"
                @click="activeRelationFilter = option.key"
              >
                <span>{{ option.label }}</span>
                <strong>{{ option.count }}</strong>
              </button>
            </div>

            <template v-if="selectedLinks.length === 0">
              <div class="empty-tip">当前节点在筛选结果中没有可见关系。</div>
            </template>
            <template v-else-if="visibleSelectedLinkGroups.length === 0">
              <div class="empty-tip">当前筛选条件下没有可见关系。</div>
            </template>
            <template v-else>
              <section
                v-for="group in visibleSelectedLinkGroups"
                :key="group.type"
                class="related-group"
              >
                <header class="related-group-head">
                  <div class="related-group-title">
                    <span
                      class="related-dot"
                      :style="{ backgroundColor: LINK_META[group.type].accent }"
                    />
                    <span>{{ LINK_META[group.type].label }}</span>
                  </div>
                  <span class="related-group-count">{{ group.links.length }}</span>
                </header>

                <button
                  v-for="link in group.links"
                  :key="`${link.type}-${link.source}-${link.target}`"
                  type="button"
                  class="related-item"
                  @click="link.otherNode && selectNode(link.otherNode.id)"
                >
                  <span class="related-name">{{ link.otherNode?.label || '未命名节点' }}</span>
                  <span class="related-meta">{{ LINK_META[link.type].label }} · {{ link.label }}</span>
                </button>
              </section>
            </template>
          </div>
        </template>

        <template v-else>
          <div class="empty-detail">
            <h3>选择一个节点</h3>
            <p>从图谱中点击任意节点，即可查看它的描述、关系数和跳转入口。</p>
          </div>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.graph-page {
  min-height: 100vh;
  padding: 28px;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.12), transparent 26%),
    radial-gradient(circle at bottom right, rgba(15, 118, 110, 0.14), transparent 24%),
    linear-gradient(180deg, #f8fbff 0%, #f2f7fb 100%);
}

.graph-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.header-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.back-btn {
  margin-top: 2px;
}

.eyebrow {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #2563eb;
}

.graph-header h1 {
  margin: 6px 0 10px;
  font-size: 32px;
  line-height: 1.1;
  color: #0f172a;
}

.graph-header p {
  margin: 0;
  max-width: 760px;
  color: #475569;
  line-height: 1.7;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.summary-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.06);
  backdrop-filter: blur(16px);
}

.summary-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 16px;
  font-size: 20px;
}

.notebook-card .summary-icon {
  background: rgba(245, 158, 11, 0.16);
  color: #b7791f;
}

.note-card .summary-icon {
  background: rgba(37, 99, 235, 0.14);
  color: #2563eb;
}

.tag-card .summary-icon {
  background: rgba(15, 118, 110, 0.14);
  color: #0f766e;
}

.relation-card .summary-icon {
  background: rgba(217, 119, 6, 0.14);
  color: #b45309;
}

.summary-value {
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
  color: #0f172a;
}

.summary-label {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.control-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  margin-bottom: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow: 0 16px 36px rgba(15, 23, 42, 0.05);
}

.search-input {
  flex: 1;
  min-width: 240px;
}

.toggle-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.type-toggle {
  border: 1px solid rgba(148, 163, 184, 0.22);
  background: rgba(255, 255, 255, 0.96);
  color: #334155;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
}

.type-toggle.active {
  border-color: rgba(37, 99, 235, 0.36);
  background: rgba(239, 246, 255, 0.98);
  color: #1d4ed8;
}

.switch-wrap {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  color: #475569;
  font-size: 13px;
  white-space: nowrap;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) 340px;
  gap: 18px;
}

.graph-stage,
.detail-panel {
  min-height: 0;
  border-radius: 26px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow: 0 24px 48px rgba(15, 23, 42, 0.07);
  backdrop-filter: blur(18px);
}

.graph-stage {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 760px;
}

.stage-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 0;
}

.stage-head h2 {
  margin: 0 0 6px;
  font-size: 20px;
  color: #0f172a;
}

.stage-head p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #475569;
  font-size: 12px;
}

.legend-dot,
.legend-line {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 999px;
}

.legend-dot.notebook {
  background: #b7791f;
}

.legend-dot.note {
  background: #2563eb;
}

.legend-dot.tag {
  background: #0f766e;
}

.legend-line {
  width: 18px;
  height: 2px;
  border-radius: 0;
  background: rgba(217, 119, 6, 0.72);
}

.legend-line.related {
  background: transparent;
  border-top: 2px dashed rgba(217, 119, 6, 0.72);
}

.graph-host {
  position: relative;
  flex: none;
  height: clamp(520px, 68vh, 760px);
  min-height: 520px;
  margin: 16px;
  border-radius: 24px;
  overflow: hidden;
  background:
    radial-gradient(circle at center, rgba(37, 99, 235, 0.05), transparent 36%),
    linear-gradient(180deg, #f8fbff 0%, #f5f8fc 100%);
  border: 1px solid rgba(148, 163, 184, 0.14);
}

.graph-host.dragging {
  cursor: grabbing;
}

.graph-svg {
  display: block;
  width: 100%;
  height: 100%;
  touch-action: none;
}

.state-panel {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: rgba(248, 250, 252, 0.9);
  color: #475569;
  z-index: 2;
}

.graph-link {
  transition: opacity 0.2s ease;
}

.graph-link.dimmed {
  opacity: 0.12;
}

.graph-node {
  cursor: pointer;
  transition: opacity 0.18s ease;
}

.graph-node.dragging,
.graph-node.dragging .node-core,
.graph-node.dragging .node-halo {
  cursor: grabbing;
}

.node-halo {
  opacity: 0.7;
}

.node-core {
  filter: drop-shadow(0 14px 28px rgba(15, 23, 42, 0.16));
  transition: filter 0.18s ease;
}

.graph-node.dragging .node-core {
  filter: drop-shadow(0 22px 34px rgba(15, 23, 42, 0.24));
}

.node-ring {
  fill: none;
  stroke: rgba(15, 23, 42, 0.52);
  stroke-width: 2;
}

.node-label {
  font-size: 12px;
  font-weight: 700;
  fill: #0f172a;
  paint-order: stroke fill;
  stroke: rgba(255, 255, 255, 0.9);
  stroke-width: 4px;
  stroke-linejoin: round;
}

.detail-panel {
  padding: 20px;
}

.detail-head h3 {
  margin: 12px 0 8px;
  font-size: 24px;
  color: #0f172a;
}

.detail-head p {
  margin: 0;
  color: #475569;
  line-height: 1.7;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 18px 0;
}

.detail-metric {
  padding: 12px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.96), rgba(241, 245, 249, 0.92));
  border: 1px solid rgba(148, 163, 184, 0.12);
}

.detail-metric span {
  display: block;
  font-size: 12px;
  color: #64748b;
}

.detail-metric strong {
  display: block;
  margin-top: 8px;
  font-size: 22px;
  color: #0f172a;
}

.detail-meta {
  margin: 18px 0;
  padding: 14px 16px;
  border-radius: 18px;
  background: rgba(248, 250, 252, 0.8);
}

.detail-actions {
  display: grid;
  gap: 10px;
}

.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
  color: #334155;
}

.meta-row + .meta-row {
  margin-top: 10px;
}

.meta-label {
  color: #64748b;
}

.related-list {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.related-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.related-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.related-total,
.related-group-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(226, 232, 240, 0.72);
  color: #334155;
  font-size: 12px;
  font-weight: 700;
}

.relation-filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.relation-filter {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(255, 255, 255, 0.78);
  color: #475569;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.relation-filter strong {
  font-size: 12px;
  font-weight: 700;
}

.relation-filter:hover {
  transform: translateY(-1px);
  border-color: rgba(71, 85, 105, 0.28);
}

.relation-filter.is-active {
  color: var(--filter-accent);
  border-color: var(--filter-accent);
  background: var(--filter-soft);
}

.related-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.related-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.related-group-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
}

.related-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
}

.related-item {
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.14);
  background: rgba(248, 250, 252, 0.78);
  transition: border-color 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
}

.related-item:hover {
  transform: translateY(-1px);
  border-color: rgba(37, 99, 235, 0.18);
  box-shadow: 0 14px 26px rgba(15, 23, 42, 0.08);
}

.related-name {
  display: block;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.related-meta {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.empty-tip,
.empty-detail p {
  color: #64748b;
  line-height: 1.7;
}

.empty-detail h3 {
  margin: 0 0 8px;
  font-size: 24px;
  color: #0f172a;
}

@media (max-width: 1200px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workspace {
    grid-template-columns: 1fr;
  }

  .detail-panel {
    order: -1;
  }
}

@media (max-width: 860px) {
  .graph-page {
    padding: 16px;
  }

  .graph-header,
  .control-bar,
  .stage-head {
    flex-direction: column;
  }

  .switch-wrap {
    margin-left: 0;
  }

  .summary-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .graph-host {
    height: clamp(460px, 62vh, 620px);
    min-height: 460px;
    margin: 12px;
  }
}
</style>
