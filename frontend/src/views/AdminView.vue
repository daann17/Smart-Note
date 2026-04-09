<script setup lang="ts">
import {
  ArrowLeftOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  HddOutlined,
  InboxOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons-vue';
import { message, Modal } from 'ant-design-vue';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import api from '../api';
import { isStoredAdmin } from '../utils/session';

type AdminOverview = {
  totalUsers: number;
  activeUsers: number;
  disabledUsers: number;
  adminUsers: number;
  totalNotes: number;
  totalNotebooks: number;
  totalTags: number;
};

type AdminStorageOverview = {
  totalKnowledgeBytes: number;
  totalHistoryBytes: number;
  totalUploadBytes: number;
  totalEstimatedBytes: number;
  uploadFileCount: number;
};

type AdminUser = {
  id: number;
  username: string;
  email: string;
  nickname: string | null;
  role: 'USER' | 'ADMIN';
  active: boolean;
  createdAt: string;
  updatedAt: string;
  noteCount: number;
  notebookCount: number;
  tagCount: number;
};

type AdminUserStorage = {
  userId: number;
  username: string;
  nickname: string | null;
  noteCount: number;
  historyCount: number;
  noteBytes: number;
  historyBytes: number;
  totalBytes: number;
};

type AdminSearchMaintenanceLastRun = {
  status: 'IDLE' | 'SUCCESS' | 'FAILED';
  operation: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  message: string;
};

type AdminSearchMaintenance = {
  searchStrategy: string;
  searchableNotes: number;
  searchableNotebooks: number;
  searchableTags: number;
  noteTagLinks: number;
  lastRun: AdminSearchMaintenanceLastRun;
};

type UserStatusFilter = 'all' | 'active' | 'inactive';
type UserRoleFilter = 'all' | 'USER' | 'ADMIN';
type SearchMaintenanceOperation = 'analyze' | 'vacuum' | 'reindex';

const router = useRouter();
const currentUsername = localStorage.getItem('username') || '';

const overviewLoading = ref(true);
const userLoading = ref(true);
const storageOverviewLoading = ref(true);
const storageUserLoading = ref(true);
const searchMaintenanceLoading = ref(true);
const refreshing = ref(false);
const searchMaintenanceAction = ref<SearchMaintenanceOperation | null>(null);

const users = ref<AdminUser[]>([]);
const storageUsers = ref<AdminUserStorage[]>([]);
const actionLoading = reactive<Record<string, boolean>>({});

const filters = reactive<{
  keyword: string;
  status: UserStatusFilter;
  role: UserRoleFilter;
}>({
  keyword: '',
  status: 'all',
  role: 'all',
});

const overview = ref<AdminOverview>({
  totalUsers: 0,
  activeUsers: 0,
  disabledUsers: 0,
  adminUsers: 0,
  totalNotes: 0,
  totalNotebooks: 0,
  totalTags: 0,
});

const storageOverview = ref<AdminStorageOverview>({
  totalKnowledgeBytes: 0,
  totalHistoryBytes: 0,
  totalUploadBytes: 0,
  totalEstimatedBytes: 0,
  uploadFileCount: 0,
});

const searchMaintenance = ref<AdminSearchMaintenance>({
  searchStrategy: '标题、正文与标签名模糊匹配',
  searchableNotes: 0,
  searchableNotebooks: 0,
  searchableTags: 0,
  noteTagLinks: 0,
  lastRun: {
    status: 'IDLE',
    operation: null,
    startedAt: null,
    finishedAt: null,
    message: '尚未执行搜索维护操作',
  },
});

const userColumns = [
  { title: '用户', key: 'user', dataIndex: 'username' },
  { title: '角色', key: 'role', dataIndex: 'role' },
  { title: '状态', key: 'active', dataIndex: 'active' },
  { title: '知识资产', key: 'assets' },
  { title: '创建时间', key: 'createdAt', dataIndex: 'createdAt' },
  { title: '最近更新', key: 'updatedAt', dataIndex: 'updatedAt' },
  { title: '操作', key: 'actions' },
];

const storageColumns = [
  { title: '用户', key: 'user' },
  { title: '笔记数', key: 'noteCount', dataIndex: 'noteCount' },
  { title: '历史版本', key: 'historyCount', dataIndex: 'historyCount' },
  { title: '正文占用', key: 'noteBytes' },
  { title: '历史占用', key: 'historyBytes' },
  { title: '总占用', key: 'totalBytes' },
  { title: '占比', key: 'share' },
];

const summaryCards = computed(() => ([
  {
    key: 'totalUsers',
    label: '总用户数',
    value: overview.value.totalUsers,
    hint: '系统内已注册账号总量',
    tone: 'blue',
    icon: TeamOutlined,
  },
  {
    key: 'activeUsers',
    label: '启用账号',
    value: overview.value.activeUsers,
    hint: `停用 ${overview.value.disabledUsers} 个账号`,
    tone: 'green',
    icon: UserOutlined,
  },
  {
    key: 'adminUsers',
    label: '管理员',
    value: overview.value.adminUsers,
    hint: '具备后台管理权限',
    tone: 'gold',
    icon: SafetyCertificateOutlined,
  },
  {
    key: 'knowledgeAssets',
    label: '知识资产',
    value: overview.value.totalNotes,
    hint: `笔记本 ${overview.value.totalNotebooks} · 标签 ${overview.value.totalTags}`,
    tone: 'teal',
    icon: ReloadOutlined,
  },
]));

const storageCards = computed(() => ([
  {
    key: 'knowledge',
    label: '笔记正文占用',
    value: formatBytes(storageOverview.value.totalKnowledgeBytes),
    hint: '统计标题、Markdown、HTML 与摘要',
    tone: 'blue',
    icon: FileTextOutlined,
  },
  {
    key: 'history',
    label: '历史版本占用',
    value: formatBytes(storageOverview.value.totalHistoryBytes),
    hint: '所有历史快照累计估算',
    tone: 'green',
    icon: DatabaseOutlined,
  },
  {
    key: 'upload',
    label: '上传目录占用',
    value: formatBytes(storageOverview.value.totalUploadBytes),
    hint: `${storageOverview.value.uploadFileCount} 个上传文件`,
    tone: 'gold',
    icon: InboxOutlined,
  },
  {
    key: 'total',
    label: '系统总占用',
    value: formatBytes(storageOverview.value.totalEstimatedBytes),
    hint: '知识数据与上传文件合计',
    tone: 'teal',
    icon: HddOutlined,
  },
]));

const searchMaintenanceCards = computed(() => ([
  {
    key: 'notes',
    label: '可搜索笔记',
    value: searchMaintenance.value.searchableNotes,
    hint: '非回收站笔记总量',
  },
  {
    key: 'notebooks',
    label: '参与搜索的笔记本',
    value: searchMaintenance.value.searchableNotebooks,
    hint: '搜索作用域覆盖的笔记本数量',
  },
  {
    key: 'tags',
    label: '标签总量',
    value: searchMaintenance.value.searchableTags,
    hint: '用于标签联想与筛选',
  },
  {
    key: 'links',
    label: '笔记标签关联',
    value: searchMaintenance.value.noteTagLinks,
    hint: '搜索关联表中的有效映射数',
  },
]));

const managedKnowledgeBytes = computed(
  () => storageOverview.value.totalKnowledgeBytes + storageOverview.value.totalHistoryBytes,
);

const formatDateTime = (value?: string) => {
  if (!value) return '--';
  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const formatBytes = (value?: number) => {
  const bytes = Number(value || 0);
  if (bytes < 1024) return `${bytes} B`;

  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = bytes / 1024;
  let unitIndex = 0;

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }

  return `${size.toFixed(size >= 10 || unitIndex === 0 ? 1 : 2)} ${units[unitIndex]}`;
};

const formatSearchMaintenanceOperation = (value?: string | null) => {
  switch ((value || '').toLowerCase()) {
    case 'analyze':
      return 'ANALYZE';
    case 'vacuum':
      return 'VACUUM ANALYZE';
    case 'reindex':
      return 'REINDEX';
    default:
      return '未执行';
  }
};

const searchMaintenanceStatusText = computed(() => {
  switch (searchMaintenance.value.lastRun.status) {
    case 'SUCCESS':
      return '最近执行成功';
    case 'FAILED':
      return '最近执行失败';
    default:
      return '尚未执行';
  }
});

const searchMaintenanceStatusColor = computed(() => {
  switch (searchMaintenance.value.lastRun.status) {
    case 'SUCCESS':
      return 'success';
    case 'FAILED':
      return 'error';
    default:
      return 'default';
  }
});

const searchMaintenanceLastTimeText = computed(() => {
  const finishedAt = searchMaintenance.value.lastRun.finishedAt;
  const startedAt = searchMaintenance.value.lastRun.startedAt;
  return formatDateTime(finishedAt || startedAt || undefined);
});

const getStorageShare = (bytes: number) => {
  if (managedKnowledgeBytes.value <= 0) return 0;
  return Number(((bytes / managedKnowledgeBytes.value) * 100).toFixed(1));
};

const setActionLoading = (key: string, value: boolean) => {
  if (value) {
    actionLoading[key] = true;
    return;
  }

  delete actionLoading[key];
};

const isActionBusy = (key: string) => Boolean(actionLoading[key]);

const buildUserQuery = () => ({
  keyword: filters.keyword.trim() || undefined,
  status: filters.status,
  role: filters.role,
});

const buildStorageQuery = () => ({
  keyword: filters.keyword.trim() || undefined,
});

const handleAdminAccessError = (error: any) => {
  if (error?.response?.status === 403) {
    message.error('当前账号没有管理员权限');
    router.replace('/home');
    return true;
  }

  return false;
};

const loadOverview = async (silent = false) => {
  if (!silent) overviewLoading.value = true;

  try {
    const response = await api.get<AdminOverview>('/admin/overview');
    overview.value = response.data;
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '管理员概览加载失败');
    }
  } finally {
    overviewLoading.value = false;
  }
};

const loadStorageOverview = async (silent = false) => {
  if (!silent) storageOverviewLoading.value = true;

  try {
    const response = await api.get<AdminStorageOverview>('/admin/storage/overview');
    storageOverview.value = response.data;
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '存储概览加载失败');
    }
  } finally {
    storageOverviewLoading.value = false;
  }
};

const loadUsers = async (silent = false) => {
  if (!silent) userLoading.value = true;

  try {
    const response = await api.get<AdminUser[]>('/admin/users', {
      params: buildUserQuery(),
    });
    users.value = response.data;
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '用户列表加载失败');
    }
  } finally {
    userLoading.value = false;
  }
};

const loadStorageUsers = async (silent = false) => {
  if (!silent) storageUserLoading.value = true;

  try {
    const response = await api.get<AdminUserStorage[]>('/admin/storage/users', {
      params: buildStorageQuery(),
    });
    storageUsers.value = response.data;
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '用户存储统计加载失败');
    }
  } finally {
    storageUserLoading.value = false;
  }
};

const loadSearchMaintenance = async (silent = false) => {
  if (!silent) searchMaintenanceLoading.value = true;

  try {
    const response = await api.get<AdminSearchMaintenance>('/admin/search/maintenance');
    searchMaintenance.value = response.data;
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '搜索维护信息加载失败');
    }
  } finally {
    searchMaintenanceLoading.value = false;
  }
};

const refreshAll = async () => {
  refreshing.value = true;
  await Promise.allSettled([
    loadOverview(true),
    loadStorageOverview(true),
    loadSearchMaintenance(true),
    loadUsers(true),
    loadStorageUsers(true),
  ]);
  refreshing.value = false;
};

const applyFilters = async () => {
  await Promise.allSettled([
    loadUsers(),
    loadStorageUsers(),
  ]);
};

const resetFilters = async () => {
  filters.keyword = '';
  filters.status = 'all';
  filters.role = 'all';
  await applyFilters();
};

const syncUser = (nextUser: AdminUser) => {
  users.value = users.value.map((user) => (user.id === nextUser.id ? nextUser : user));
};

const handleToggleUserStatus = async (user: AdminUser, active: boolean) => {
  const loadingKey = `status-${user.id}`;
  setActionLoading(loadingKey, true);

  try {
    const response = await api.put<AdminUser>(`/admin/users/${user.id}/status`, { active });
    syncUser(response.data);
    message.success(active ? '账号已启用' : '账号已停用');
    await loadOverview(true);
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '账号状态更新失败');
    }
  } finally {
    setActionLoading(loadingKey, false);
  }
};

const handleChangeUserRole = async (user: AdminUser, role: AdminUser['role']) => {
  if (user.role === role) return;

  const loadingKey = `role-${user.id}`;
  setActionLoading(loadingKey, true);

  try {
    const response = await api.put<AdminUser>(`/admin/users/${user.id}/role`, { role });
    syncUser(response.data);
    message.success(role === 'ADMIN' ? '已授予管理员权限' : '已调整为普通用户');
    await loadOverview(true);
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || '用户角色更新失败');
    }
  } finally {
    setActionLoading(loadingKey, false);
  }
};

const onUserStatusChange = (user: AdminUser, checked: boolean) => {
  void handleToggleUserStatus(user, checked);
};

const onUserRoleChange = (user: AdminUser, nextRole: AdminUser['role']) => {
  void handleChangeUserRole(user, nextRole);
};

const runSearchMaintenance = async (operation: SearchMaintenanceOperation) => {
  searchMaintenanceAction.value = operation;

  try {
    const response = await api.post<AdminSearchMaintenance>(`/admin/search/maintenance/${operation}`);
    searchMaintenance.value = response.data;
    message.success(`${formatSearchMaintenanceOperation(operation)} 已执行完成`);
  } catch (error: any) {
    if (!handleAdminAccessError(error)) {
      message.error(error.response?.data?.message || `${formatSearchMaintenanceOperation(operation)} 执行失败`);
    }
  } finally {
    searchMaintenanceAction.value = null;
  }
};

const handleSearchMaintenance = (operation: SearchMaintenanceOperation) => {
  if (operation === 'analyze') {
    void runSearchMaintenance(operation);
    return;
  }

  const isReindex = operation === 'reindex';
  Modal.confirm({
    title: isReindex ? '执行 REINDEX 维护？' : '执行 VACUUM ANALYZE 维护？',
    content: isReindex
      ? '该操作会重建搜索相关表索引，开销更高，建议在低峰期执行。是否继续？'
      : '该操作会清理并分析搜索相关表，适合作为定期维护操作。是否继续？',
    okText: '继续执行',
    cancelText: '取消',
    onOk: () => runSearchMaintenance(operation),
  });
};

onMounted(async () => {
  if (!isStoredAdmin()) {
    router.replace('/home');
    return;
  }

  await Promise.allSettled([
    loadOverview(),
    loadStorageOverview(),
    loadSearchMaintenance(),
    loadUsers(),
    loadStorageUsers(),
  ]);
});
</script>

<template>
  <div class="admin-page">
    <header class="admin-header">
      <div class="header-main">
        <a-button type="text" class="back-btn" @click="router.push('/home')">
          <template #icon><ArrowLeftOutlined /></template>
          返回工作台
        </a-button>

        <div>
          <p class="eyebrow">Admin Console</p>
          <h1>管理员中心</h1>
          <p class="header-copy">
            统一查看系统账号、知识资产与存储占用情况，并对用户进行启用、停用与角色调整。
          </p>
        </div>
      </div>

      <a-button type="primary" :loading="refreshing" @click="refreshAll">
        <template #icon><ReloadOutlined /></template>
        刷新数据
      </a-button>
    </header>

    <section class="summary-grid">
      <a-card
        v-for="card in summaryCards"
        :key="card.key"
        class="summary-card"
        :class="`tone-${card.tone}`"
        :loading="overviewLoading"
      >
        <div class="summary-icon">
          <component :is="card.icon" />
        </div>
        <div class="summary-body">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.hint }}</small>
        </div>
      </a-card>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>搜索维护</h2>
          <p>围绕搜索相关表提供可视化维护入口，便于管理员在数据量增长后刷新统计、清理膨胀并重建索引。</p>
        </div>
        <a-tag :color="searchMaintenanceStatusColor">{{ searchMaintenanceStatusText }}</a-tag>
      </div>

      <div class="maintenance-card-grid">
        <a-card
          v-for="card in searchMaintenanceCards"
          :key="card.key"
          class="maintenance-card"
          :loading="searchMaintenanceLoading"
        >
          <div class="maintenance-metric">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.hint }}</small>
          </div>
        </a-card>
      </div>

      <div class="maintenance-status-box">
        <div class="maintenance-status-row">
          <span class="maintenance-status-label">当前搜索策略</span>
          <strong>{{ searchMaintenance.searchStrategy }}</strong>
        </div>
        <div class="maintenance-status-row">
          <span class="maintenance-status-label">最近执行</span>
          <strong>{{ formatSearchMaintenanceOperation(searchMaintenance.lastRun.operation) }}</strong>
        </div>
        <div class="maintenance-status-row">
          <span class="maintenance-status-label">最近时间</span>
          <strong>{{ searchMaintenanceLastTimeText }}</strong>
        </div>
        <p class="maintenance-status-message">{{ searchMaintenance.lastRun.message }}</p>
      </div>

      <div class="maintenance-actions">
        <a-button @click="loadSearchMaintenance()" :loading="searchMaintenanceLoading && !searchMaintenanceAction">
          刷新状态
        </a-button>
        <a-button
          type="primary"
          ghost
          :loading="searchMaintenanceAction === 'analyze'"
          @click="handleSearchMaintenance('analyze')"
        >
          执行 ANALYZE
        </a-button>
        <a-button
          :loading="searchMaintenanceAction === 'vacuum'"
          @click="handleSearchMaintenance('vacuum')"
        >
          执行 VACUUM ANALYZE
        </a-button>
        <a-button
          danger
          :loading="searchMaintenanceAction === 'reindex'"
          @click="handleSearchMaintenance('reindex')"
        >
          执行 REINDEX
        </a-button>
      </div>

      <p class="maintenance-footnote">
        `ANALYZE` 用于刷新查询统计信息，`VACUUM ANALYZE` 适合常规维护，`REINDEX` 开销最大，建议在业务低峰期执行。
      </p>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>存储统计</h2>
          <p>按笔记正文、历史版本和上传目录估算系统占用，并展示用户侧的知识数据排行。</p>
        </div>
        <span class="result-count">当前 {{ storageUsers.length }} 位用户</span>
      </div>

      <div class="storage-card-grid">
        <a-card
          v-for="card in storageCards"
          :key="card.key"
          class="storage-card"
          :class="`tone-${card.tone}`"
          :loading="storageOverviewLoading"
        >
          <div class="summary-icon">
            <component :is="card.icon" />
          </div>
          <div class="summary-body">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.hint }}</small>
          </div>
        </a-card>
      </div>

      <a-table
        class="storage-table"
        :columns="storageColumns"
        :data-source="storageUsers"
        :loading="storageUserLoading"
        :pagination="{ pageSize: 8, showSizeChanger: false }"
        row-key="userId"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'user'">
            <div class="user-cell">
              <div class="user-avatar">{{ (record.nickname || record.username).slice(0, 1).toUpperCase() }}</div>
              <div>
                <strong>{{ record.nickname || record.username }}</strong>
                <div class="sub-line">@{{ record.username }}</div>
              </div>
            </div>
          </template>

          <template v-else-if="column.key === 'noteBytes'">
            <span>{{ formatBytes(record.noteBytes) }}</span>
          </template>

          <template v-else-if="column.key === 'historyBytes'">
            <span>{{ formatBytes(record.historyBytes) }}</span>
          </template>

          <template v-else-if="column.key === 'totalBytes'">
            <strong class="storage-total">{{ formatBytes(record.totalBytes) }}</strong>
          </template>

          <template v-else-if="column.key === 'share'">
            <div class="share-cell">
              <a-progress :percent="getStorageShare(record.totalBytes)" size="small" :show-info="false" />
              <span>{{ getStorageShare(record.totalBytes).toFixed(1) }}%</span>
            </div>
          </template>
        </template>
      </a-table>

      <p class="storage-footnote">
        上传目录当前按系统总量统计，尚未精确归属到单个用户；用户排行仅统计笔记正文与历史版本数据。
      </p>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>用户管理</h2>
          <p>支持按关键词、角色与状态筛选，并直接调整账号状态。</p>
        </div>
        <span class="result-count">当前 {{ users.length }} 位用户</span>
      </div>

      <div class="toolbar">
        <a-input
          v-model:value="filters.keyword"
          allow-clear
          class="toolbar-search"
          placeholder="搜索用户名、邮箱或昵称"
          @pressEnter="applyFilters"
        />
        <a-select v-model:value="filters.status" class="toolbar-select" @change="applyFilters">
          <a-select-option value="all">全部状态</a-select-option>
          <a-select-option value="active">仅启用</a-select-option>
          <a-select-option value="inactive">仅停用</a-select-option>
        </a-select>
        <a-select v-model:value="filters.role" class="toolbar-select" @change="applyFilters">
          <a-select-option value="all">全部角色</a-select-option>
          <a-select-option value="ADMIN">管理员</a-select-option>
          <a-select-option value="USER">普通用户</a-select-option>
        </a-select>
        <a-button type="primary" @click="applyFilters">查询</a-button>
        <a-button @click="resetFilters">重置</a-button>
      </div>

      <a-table
        :columns="userColumns"
        :data-source="users"
        :loading="userLoading"
        :pagination="{ pageSize: 8, showSizeChanger: false }"
        row-key="id"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'user'">
            <div class="user-cell">
              <div class="user-avatar">{{ (record.nickname || record.username).slice(0, 1).toUpperCase() }}</div>
              <div>
                <strong>{{ record.nickname || record.username }}</strong>
                <div class="sub-line">@{{ record.username }}</div>
                <div class="sub-line">{{ record.email }}</div>
              </div>
            </div>
          </template>

          <template v-else-if="column.key === 'role'">
            <a-tag :color="record.role === 'ADMIN' ? 'gold' : 'blue'">
              {{ record.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'active'">
            <a-switch
              :checked="record.active"
              checked-children="启用"
              un-checked-children="停用"
              :loading="isActionBusy(`status-${record.id}`)"
              :disabled="record.username === currentUsername"
              @change="onUserStatusChange(record, $event)"
            />
          </template>

          <template v-else-if="column.key === 'assets'">
            <div class="asset-cell">
              <span>笔记 {{ record.noteCount }}</span>
              <span>笔记本 {{ record.notebookCount }}</span>
              <span>标签 {{ record.tagCount }}</span>
            </div>
          </template>

          <template v-else-if="column.key === 'createdAt'">
            <span>{{ formatDateTime(record.createdAt) }}</span>
          </template>

          <template v-else-if="column.key === 'updatedAt'">
            <span>{{ formatDateTime(record.updatedAt) }}</span>
          </template>

          <template v-else-if="column.key === 'actions'">
            <a-select
              :value="record.role"
              class="role-select"
              :disabled="record.username === currentUsername || isActionBusy(`role-${record.id}`)"
              :loading="isActionBusy(`role-${record.id}`)"
              @change="onUserRoleChange(record, $event)"
            >
              <a-select-option value="USER">普通用户</a-select-option>
              <a-select-option value="ADMIN">管理员</a-select-option>
            </a-select>
          </template>
        </template>
      </a-table>
    </section>
  </div>
</template>

<style scoped>
.admin-page {
  min-height: 100vh;
  padding: 28px;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.12), transparent 24%),
    radial-gradient(circle at top right, rgba(245, 158, 11, 0.12), transparent 22%),
    linear-gradient(180deg, #f8fafc 0%, #eef4f8 100%);
}

.admin-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
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
  margin: 0;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.admin-header h1 {
  margin: 6px 0 10px;
  font-size: 34px;
  line-height: 1.1;
  color: #0f172a;
}

.header-copy {
  margin: 0;
  max-width: 760px;
  color: #475569;
  line-height: 1.7;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card,
.storage-card {
  border-radius: 22px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 18px 38px rgba(15, 23, 42, 0.06);
}

.summary-card :deep(.ant-card-body),
.storage-card :deep(.ant-card-body) {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 22px;
}

.summary-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 18px;
  font-size: 22px;
}

.summary-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-body span {
  color: #475569;
  font-size: 13px;
}

.summary-body strong {
  color: #0f172a;
  font-size: 28px;
  line-height: 1;
}

.summary-body small {
  color: #64748b;
  font-size: 12px;
}

.tone-blue .summary-icon {
  background: rgba(37, 99, 235, 0.12);
  color: #2563eb;
}

.tone-green .summary-icon {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.tone-gold .summary-icon {
  background: rgba(245, 158, 11, 0.14);
  color: #d97706;
}

.tone-teal .summary-icon {
  background: rgba(13, 148, 136, 0.12);
  color: #0f766e;
}

.panel {
  padding: 22px;
  border-radius: 26px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 22px 44px rgba(15, 23, 42, 0.07);
  backdrop-filter: blur(14px);
}

.panel + .panel {
  margin-top: 20px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.panel-head h2 {
  margin: 0;
  color: #0f172a;
  font-size: 22px;
}

.panel-head p {
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.result-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 116px;
  height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  background: rgba(226, 232, 240, 0.74);
  color: #334155;
  font-size: 13px;
  font-weight: 700;
}

.storage-card-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.maintenance-card-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.maintenance-card {
  border-radius: 20px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 18px 38px rgba(15, 23, 42, 0.05);
}

.maintenance-card :deep(.ant-card-body) {
  padding: 20px;
}

.maintenance-metric {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.maintenance-metric span {
  color: #475569;
  font-size: 13px;
}

.maintenance-metric strong {
  color: #0f172a;
  font-size: 28px;
  line-height: 1;
}

.maintenance-metric small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.maintenance-status-box {
  padding: 16px 18px;
  border-radius: 20px;
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.9), rgba(248, 250, 252, 0.92));
  border: 1px solid rgba(191, 219, 254, 0.8);
}

.maintenance-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.maintenance-status-row + .maintenance-status-row {
  margin-top: 10px;
}

.maintenance-status-label {
  color: #475569;
  font-size: 13px;
}

.maintenance-status-row strong {
  color: #0f172a;
  font-size: 14px;
}

.maintenance-status-message {
  margin: 12px 0 0;
  color: #334155;
  line-height: 1.7;
}

.maintenance-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 18px;
}

.maintenance-footnote {
  margin: 12px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}

.storage-table {
  margin-top: 4px;
}

.storage-total {
  color: #0f172a;
}

.storage-footnote {
  margin: 12px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}

.share-cell {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 18px;
}

.toolbar-search {
  width: min(340px, 100%);
}

.toolbar-select {
  width: 140px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 14px;
  background: linear-gradient(135deg, #dbeafe, #e0f2fe);
  color: #1d4ed8;
  font-size: 16px;
  font-weight: 700;
}

.user-cell strong {
  display: block;
  color: #0f172a;
  font-size: 14px;
}

.sub-line {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.asset-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #334155;
  font-size: 12px;
}

.role-select {
  width: 116px;
}

@media (max-width: 1180px) {
  .summary-grid,
  .storage-card-grid,
  .maintenance-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .admin-page {
    padding: 16px;
  }

  .admin-header,
  .panel-head {
    flex-direction: column;
  }

  .summary-grid,
  .storage-card-grid,
  .maintenance-card-grid {
    grid-template-columns: 1fr;
  }

  .maintenance-status-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .toolbar-search,
  .toolbar-select {
    width: 100%;
  }
}

/* Notion-inspired overrides */
.admin-page {
  max-width: 1360px;
  margin: 0 auto;
  padding: 28px 24px 40px;
  background: transparent;
}

.admin-header {
  margin-bottom: 24px;
}

.header-main {
  gap: 16px;
}

.back-btn {
  margin-top: 4px;
  border: 1px solid var(--sn-border);
  border-radius: var(--sn-radius-pill);
  background: rgba(0, 0, 0, 0.04);
}

.eyebrow {
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

.admin-header h1 {
  margin: 12px 0 10px;
  font-size: clamp(32px, 4vw, 52px);
  line-height: 1.04;
  letter-spacing: -1.4px;
  color: var(--sn-text);
}

.header-copy {
  max-width: 780px;
  color: var(--sn-text-soft);
  font-size: 16px;
}

.summary-grid,
.storage-card-grid,
.maintenance-card-grid {
  gap: 16px;
}

.summary-card,
.storage-card,
.maintenance-card {
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid var(--sn-border);
  box-shadow: var(--sn-shadow-card);
}

.summary-card :deep(.ant-card-body),
.storage-card :deep(.ant-card-body),
.maintenance-card :deep(.ant-card-body) {
  padding: 22px;
}

.summary-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
}

.summary-body span,
.maintenance-metric span {
  color: var(--sn-text-soft);
}

.summary-body strong,
.maintenance-metric strong {
  color: var(--sn-text);
  font-size: 30px;
  letter-spacing: -0.8px;
}

.summary-body small,
.maintenance-metric small {
  color: var(--sn-text-muted);
}

.panel {
  padding: 24px;
  border-radius: 20px;
  background: var(--sn-surface);
  border: 1px solid var(--sn-border);
  box-shadow: var(--sn-shadow-card);
  backdrop-filter: none;
}

.panel-head h2 {
  font-size: 24px;
  line-height: 1.15;
  letter-spacing: -0.4px;
  color: var(--sn-text);
}

.panel-head p {
  color: var(--sn-text-soft);
}

.result-count {
  min-width: 0;
  height: 32px;
  padding: 0 12px;
  background: var(--sn-blue-soft);
  color: var(--sn-focus);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.maintenance-status-box {
  padding: 18px;
  border-radius: 16px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfaf8 100%);
  border: 1px solid var(--sn-border);
}

.maintenance-status-label,
.maintenance-status-message,
.maintenance-footnote,
.storage-footnote {
  color: var(--sn-text-soft);
}

.maintenance-status-row strong,
.storage-total {
  color: var(--sn-text);
}

.maintenance-actions {
  gap: 10px;
}

.toolbar {
  gap: 10px;
}

.user-avatar {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(0, 117, 222, 0.12), rgba(242, 249, 255, 0.92));
  color: var(--sn-focus);
}

.user-cell strong {
  color: var(--sn-text);
}

.sub-line {
  color: var(--sn-text-muted);
}

.asset-cell {
  color: var(--sn-text-soft);
}

.share-cell {
  gap: 12px;
}

.panel :deep(.ant-table-wrapper) {
  overflow: hidden;
  border: 1px solid var(--sn-border);
  border-radius: 16px;
  box-shadow: none;
  background: var(--sn-surface);
}

.panel :deep(.ant-table-pagination) {
  margin: 16px 16px 0 !important;
}

.panel :deep(.ant-progress-bg) {
  background: var(--sn-blue);
}

.panel :deep(.ant-select-selector),
.panel :deep(.ant-input),
.panel :deep(.ant-input-affix-wrapper) {
  background: #ffffff !important;
}

@media (max-width: 860px) {
  .admin-page {
    padding: 16px;
  }

  .admin-header,
  .panel-head {
    flex-direction: column;
  }

  .summary-grid,
  .storage-card-grid,
  .maintenance-card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
