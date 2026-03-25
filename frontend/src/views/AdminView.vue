<script setup lang="ts">
import { ArrowLeftOutlined, ReloadOutlined, SafetyCertificateOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue';
import { message } from 'ant-design-vue';
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

type UserStatusFilter = 'all' | 'active' | 'inactive';
type UserRoleFilter = 'all' | 'USER' | 'ADMIN';

const router = useRouter();
const currentUsername = localStorage.getItem('username') || '';
const overviewLoading = ref(true);
const userLoading = ref(true);
const refreshing = ref(false);
const users = ref<AdminUser[]>([]);
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

const columns = [
  {
    title: '用户',
    key: 'user',
    dataIndex: 'username',
  },
  {
    title: '角色',
    key: 'role',
    dataIndex: 'role',
  },
  {
    title: '状态',
    key: 'active',
    dataIndex: 'active',
  },
  {
    title: '知识资产',
    key: 'assets',
  },
  {
    title: '创建时间',
    key: 'createdAt',
    dataIndex: 'createdAt',
  },
  {
    title: '最近更新',
    key: 'updatedAt',
    dataIndex: 'updatedAt',
  },
  {
    title: '操作',
    key: 'actions',
  },
];

const summaryCards = computed(() => ([
  {
    key: 'totalUsers',
    label: '总用户数',
    value: overview.value.totalUsers,
    hint: '系统中的账号总量',
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
    hint: '具备系统管理权限',
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

const formatDateTime = (value?: string) => {
  if (!value) {
    return '--';
  }

  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
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

const handleAdminAccessError = (error: any) => {
  if (error?.response?.status === 403) {
    message.error('当前账号没有管理员权限');
    router.replace('/home');
    return true;
  }

  return false;
};

const loadOverview = async (silent = false) => {
  if (!silent) {
    overviewLoading.value = true;
  }

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

const loadUsers = async (silent = false) => {
  if (!silent) {
    userLoading.value = true;
  }

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

const refreshAll = async () => {
  refreshing.value = true;
  await Promise.allSettled([
    loadOverview(true),
    loadUsers(true),
  ]);
  refreshing.value = false;
};

const resetFilters = async () => {
  filters.keyword = '';
  filters.status = 'all';
  filters.role = 'all';
  await loadUsers();
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
  if (user.role === role) {
    return;
  }

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

onMounted(async () => {
  if (!isStoredAdmin()) {
    router.replace('/home');
    return;
  }

  await Promise.allSettled([
    loadOverview(),
    loadUsers(),
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
            统一查看系统账号、知识资产与权限状态，并对用户进行启用、停用与角色调整。
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
          @pressEnter="loadUsers()"
        />
        <a-select v-model:value="filters.status" class="toolbar-select" @change="loadUsers()">
          <a-select-option value="all">全部状态</a-select-option>
          <a-select-option value="active">仅启用</a-select-option>
          <a-select-option value="inactive">仅停用</a-select-option>
        </a-select>
        <a-select v-model:value="filters.role" class="toolbar-select" @change="loadUsers()">
          <a-select-option value="all">全部角色</a-select-option>
          <a-select-option value="ADMIN">管理员</a-select-option>
          <a-select-option value="USER">普通用户</a-select-option>
        </a-select>
        <a-button type="primary" @click="loadUsers()">查询</a-button>
        <a-button @click="resetFilters">重置</a-button>
      </div>

      <a-table
        :columns="columns"
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
  max-width: 720px;
  color: #475569;
  line-height: 1.7;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card {
  border-radius: 22px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 18px 38px rgba(15, 23, 42, 0.06);
}

.summary-card :deep(.ant-card-body) {
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
  .summary-grid {
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

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .toolbar-search,
  .toolbar-select {
    width: 100%;
  }
}
</style>
