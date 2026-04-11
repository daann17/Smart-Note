<script setup lang="ts">
import { computed, createVNode, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import {
  ApartmentOutlined,
  BookOutlined,
  DeleteOutlined,
  ExclamationCircleOutlined,
  HomeOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
  SettingOutlined,
  ShareAltOutlined,
  TeamOutlined,
  ImportOutlined,
} from '@ant-design/icons-vue';
import { message, Modal } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import api from '../api';
import { useNotebookStore } from '../stores/notebook';
import type { Notebook } from '../stores/notebook';
import { useNoteStore } from '../stores/note';
import type { Note, NoteSearchFilters } from '../stores/note';
import { useTagStore } from '../stores/tag';
import { clearSession, storeSession } from '../utils/session';
import DashboardView from './DashboardView.vue';
import AIAssistantDrawer from '../components/AIAssistantDrawer.vue';
import ImportModal from '../components/ImportModal.vue';

type UserProfile = {
  username: string;
  email: string;
  nickname: string;
  bio: string;
  phone: string;
  birthday: string;
  role: string;
};

type SearchForm = {
  notebookId: number | undefined;
  tagName: string | undefined;
  startDate: string;
  endDate: string;
};

type SearchStateSnapshot = {
  query: string;
  filters: SearchForm;
};

type SearchResultItem = {
  note: Note;
  notebookName: string;
  titleHtml: string;
  excerptHtml: string;
  updatedLabel: string;
  matchedTags: string[];
};

type SearchResultGroup = {
  key: string;
  notebookName: string;
  count: number;
  items: SearchResultItem[];
};

const SEARCH_STATE_KEY = 'smartnote:home-search-state';

const router = useRouter();
const notebookStore = useNotebookStore();
const noteStore = useNoteStore();
const tagStore = useTagStore();

const collapsed = ref(false);
const selectedKeys = ref<string[]>(['home']);

const searchQuery = ref('');
const searchResults = ref<Note[]>([]);
const isSearching = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | null = null;

const searchFilters = reactive<SearchForm>({
  notebookId: undefined,
  tagName: undefined,
  startDate: '',
  endDate: '',
});

const isCreateModalVisible = ref(false);
const isEditModalVisible = ref(false);
const editingNotebookId = ref<number | null>(null);
const createForm = reactive({
  name: '',
  description: '',
  isPublic: false,
});

const recentNotes = ref<Note[]>([]);
const aiDrawerVisible = ref(false);
const importModalVisible = ref(false);

const profileModalVisible = ref(false);
const profileLoading = ref(false);
const profileSaving = ref(false);
const profileForm = reactive<UserProfile>({
  username: localStorage.getItem('username') || '',
  email: '',
  nickname: localStorage.getItem('displayName') || localStorage.getItem('username') || '',
  bio: '',
  phone: '',
  birthday: '',
  role: localStorage.getItem('role') || 'USER',
});

const displayName = computed(() => {
  const nickname = profileForm.nickname?.trim();
  return nickname || profileForm.username || localStorage.getItem('displayName') || localStorage.getItem('username') || '用户';
});

const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase());
const isAdminUser = computed(() => (profileForm.role || localStorage.getItem('role') || 'USER') === 'ADMIN');

const formatDateLabel = (value?: string) => {
  if (!value) return '';
  const [year, month, day] = value.split('-');
  if (!year || !month || !day) return value;
  return `${year}年${month}月${day}日`;
};

const notebookOptions = computed(() => notebookStore.notebooks.map((notebook) => ({ label: notebook.name, value: notebook.id })));
const tagOptions = computed(() => tagStore.tags.map((tag) => ({ label: tag.name, value: tag.name })));

const activeFilterCount = computed(() => {
  let count = 0;
  if (searchFilters.notebookId) count += 1;
  if (searchFilters.tagName) count += 1;
  if (searchFilters.startDate) count += 1;
  if (searchFilters.endDate) count += 1;
  return count;
});

const activeFilterTags = computed(() => {
  const labels: string[] = [];
  if (searchFilters.notebookId) {
    const notebook = notebookStore.notebooks.find((item) => item.id === searchFilters.notebookId);
    if (notebook) labels.push(`笔记本：${notebook.name}`);
  }
  if (searchFilters.tagName) labels.push(`标签：${searchFilters.tagName}`);
  if (searchFilters.startDate) labels.push(`开始：${formatDateLabel(searchFilters.startDate)}`);
  if (searchFilters.endDate) labels.push(`结束：${formatDateLabel(searchFilters.endDate)}`);
  return labels;
});

const hasInvalidDateRange = computed(() => Boolean(
  searchFilters.startDate && searchFilters.endDate && searchFilters.startDate > searchFilters.endDate,
));

const applyProfile = (profile: Partial<UserProfile>) => {
  profileForm.username = profile.username || '';
  profileForm.email = profile.email || '';
  profileForm.nickname = profile.nickname || profile.username || '';
  profileForm.bio = profile.bio || '';
  profileForm.phone = profile.phone || '';
  profileForm.birthday = profile.birthday || '';
  profileForm.role = profile.role || profileForm.role || 'USER';
};

const buildSearchFilters = (): NoteSearchFilters => ({
  notebookId: searchFilters.notebookId,
  tagName: searchFilters.tagName || undefined,
  startDate: searchFilters.startDate || undefined,
  endDate: searchFilters.endDate || undefined,
});

const escapeHtml = (value: string) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;');

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const normalizeSearchText = (value?: string) => (value || '')
  .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
  .replace(/\[[^\]]*]\([^)]*\)/g, ' ')
  .replace(/[`>#*_~\-|]/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

const highlightText = (value: string, keyword: string) => {
  const source = value || '';
  const normalizedKeyword = keyword.trim();
  if (!normalizedKeyword) return escapeHtml(source);

  const parts = source.split(new RegExp(`(${escapeRegExp(normalizedKeyword)})`, 'ig'));
  const lowerKeyword = normalizedKeyword.toLowerCase();
  return parts.map((part) => (
    part.toLowerCase() === lowerKeyword ? `<mark>${escapeHtml(part)}</mark>` : escapeHtml(part)
  )).join('');
};

const buildExcerpt = (content: string, keyword: string) => {
  const plainText = normalizeSearchText(content);
  if (!plainText) return '暂无正文预览';

  const normalizedKeyword = keyword.trim().toLowerCase();
  if (!normalizedKeyword) return plainText.length > 96 ? `${plainText.slice(0, 96)}...` : plainText;

  const matchIndex = plainText.toLowerCase().indexOf(normalizedKeyword);
  if (matchIndex < 0) return plainText.length > 96 ? `${plainText.slice(0, 96)}...` : plainText;

  const start = Math.max(0, matchIndex - 26);
  const end = Math.min(plainText.length, matchIndex + normalizedKeyword.length + 54);
  const prefix = start > 0 ? '...' : '';
  const suffix = end < plainText.length ? '...' : '';
  return `${prefix}${plainText.slice(start, end).trim()}${suffix}`;
};

const restoreSearchState = () => {
  const raw = localStorage.getItem(SEARCH_STATE_KEY);
  if (!raw) return;

  try {
    const parsed = JSON.parse(raw) as Partial<SearchStateSnapshot>;
    searchQuery.value = typeof parsed.query === 'string' ? parsed.query : '';
    searchFilters.notebookId = typeof parsed.filters?.notebookId === 'number' ? parsed.filters.notebookId : undefined;
    searchFilters.tagName = typeof parsed.filters?.tagName === 'string' ? parsed.filters.tagName : undefined;
    searchFilters.startDate = typeof parsed.filters?.startDate === 'string' ? parsed.filters.startDate : '';
    searchFilters.endDate = typeof parsed.filters?.endDate === 'string' ? parsed.filters.endDate : '';
  } catch (error) {
    console.error('Failed to restore search state:', error);
    localStorage.removeItem(SEARCH_STATE_KEY);
  }
};

const persistSearchState = () => {
  const payload: SearchStateSnapshot = {
    query: searchQuery.value,
    filters: {
      notebookId: searchFilters.notebookId,
      tagName: searchFilters.tagName,
      startDate: searchFilters.startDate,
      endDate: searchFilters.endDate,
    },
  };
  localStorage.setItem(SEARCH_STATE_KEY, JSON.stringify(payload));
};

const groupedSearchResults = computed<SearchResultGroup[]>(() => {
  const keyword = searchQuery.value.trim();
  const groups = new Map<string, SearchResultGroup>();

  for (const note of searchResults.value) {
    const notebookName = note.notebook?.name || '未分类笔记本';
    const groupKey = `${note.notebook?.id || 0}-${notebookName}`;
    const matchedTags = (note.tags || []).map((tag) => tag.name).filter((name) => name.toLowerCase().includes(keyword.toLowerCase()));

    const item: SearchResultItem = {
      note,
      notebookName,
      titleHtml: highlightText(note.title || '无标题笔记', keyword),
      excerptHtml: highlightText(buildExcerpt(note.content || '', keyword), keyword),
      updatedLabel: new Date(note.updatedAt).toLocaleString('zh-CN'),
      matchedTags,
    };

    if (!groups.has(groupKey)) {
      groups.set(groupKey, { key: groupKey, notebookName, count: 0, items: [] });
    }

    const group = groups.get(groupKey)!;
    group.items.push(item);
    group.count += 1;
  }

  return Array.from(groups.values());
});

const fetchProfile = async () => {
  profileLoading.value = true;
  try {
    const response = await api.get('/users/me');
    applyProfile(response.data);
    storeSession({
      username: response.data.username,
      displayName: response.data.nickname || response.data.username,
      role: response.data.role,
    });
  } catch (error) {
    console.error('Failed to fetch profile:', error);
  } finally {
    profileLoading.value = false;
  }
};

const openProfileModal = async () => {
  profileModalVisible.value = true;
  await fetchProfile();
};

const handleSaveProfile = async () => {
  profileSaving.value = true;
  try {
    const response = await api.put('/users/me', {
      nickname: profileForm.nickname,
      bio: profileForm.bio,
      phone: profileForm.phone,
      birthday: profileForm.birthday || null,
    });
    applyProfile(response.data);
    storeSession({
      username: response.data.username,
      displayName: response.data.nickname || response.data.username,
      role: response.data.role,
    });
    message.success('个人信息已更新');
    profileModalVisible.value = false;
  } catch (error: any) {
    message.error(error.response?.data?.message || '个人信息更新失败');
  } finally {
    profileSaving.value = false;
  }
};

const loadRecentNotes = async () => {
  recentNotes.value = await noteStore.fetchRecentNotes();
};

const handleSearch = async () => {
  const keyword = searchQuery.value.trim();
  if (!keyword) {
    searchResults.value = [];
    isSearching.value = false;
    return;
  }

  if (hasInvalidDateRange.value) {
    searchResults.value = [];
    isSearching.value = true;
    return;
  }

  isSearching.value = true;
  try {
    searchResults.value = await noteStore.searchNotes(keyword, buildSearchFilters());
  } catch (error: any) {
    searchResults.value = [];
    message.error(error.response?.data?.message || '搜索失败，请稍后重试');
  }
};

const applySearchFilters = async () => {
  if (searchQuery.value.trim()) await handleSearch();
};

const resetSearchFilters = async () => {
  searchFilters.notebookId = undefined;
  searchFilters.tagName = undefined;
  searchFilters.startDate = '';
  searchFilters.endDate = '';
  await applySearchFilters();
};

const onSearchInput = () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    void handleSearch();
  }, 300);
};

const showCreateModal = () => {
  createForm.name = '';
  createForm.description = '';
  createForm.isPublic = false;
  isCreateModalVisible.value = true;
};

const handleCreateNotebook = async () => {
  if (!createForm.name.trim()) {
    message.warning('请输入笔记本名称');
    return;
  }

  const success = await notebookStore.createNotebook(createForm.name.trim(), createForm.description.trim(), createForm.isPublic);
  if (success) {
    message.success('笔记本创建成功');
    isCreateModalVisible.value = false;
  } else {
    message.error('笔记本创建失败');
  }
};

const showEditNotebookModal = (notebook: Notebook) => {
  editingNotebookId.value = notebook.id;
  createForm.name = notebook.name;
  createForm.description = notebook.description || '';
  createForm.isPublic = notebook.isPublic;
  isEditModalVisible.value = true;
};

const handleEditNotebook = async () => {
  if (!editingNotebookId.value || !createForm.name.trim()) {
    message.warning('请输入笔记本名称');
    return;
  }

  const success = await notebookStore.updateNotebook(editingNotebookId.value, {
    name: createForm.name.trim(),
    description: createForm.description.trim(),
    isPublic: createForm.isPublic,
  });
  if (success) {
    message.success('笔记本更新成功');
    isEditModalVisible.value = false;
    editingNotebookId.value = null;
  } else {
    message.error('笔记本更新失败');
  }
};

const handleDeleteNotebook = (id: number) => {
  Modal.confirm({
    title: '确认删除笔记本？',
    icon: createVNode(ExclamationCircleOutlined),
    content: '笔记本及其中的笔记会被移至回收站，你仍可以在回收站中恢复。',
    okText: '移至回收站',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      const success = await notebookStore.deleteNotebook(id);
      if (success) {
        message.success('已移至回收站');
        await loadRecentNotes();
      } else {
        message.error('删除失败');
      }
    },
  });
};

const handleLogout = () => {
  clearSession();
  router.push('/');
};

const handleImport = () => {
  importModalVisible.value = true;
};

const openNote = (note: Note) => {
  isSearching.value = false;
  router.push(`/notebook/${note.notebook?.id}?noteId=${note.id}`);
};

watch(
  [searchQuery, () => searchFilters.notebookId, () => searchFilters.tagName, () => searchFilters.startDate, () => searchFilters.endDate],
  () => {
    persistSearchState();
  },
);

onMounted(async () => {
  restoreSearchState();
  await Promise.allSettled([
    notebookStore.fetchNotebooks(),
    tagStore.fetchTags(),
    loadRecentNotes(),
    fetchProfile(),
  ]);

  if (searchQuery.value.trim()) {
    await handleSearch();
  }
});

onUnmounted(() => {
  if (searchTimer) clearTimeout(searchTimer);
});
</script>

<template>
  <a-layout class="layout-container">
    <a-layout-sider v-model:collapsed="collapsed" :trigger="null" collapsible theme="light" class="sider">
      <div class="logo">
        <img src="/home-logo.png" alt="Logo" />
        <span v-if="!collapsed">SmartNote</span>
      </div>

      <a-menu v-model:selectedKeys="selectedKeys" theme="light" mode="inline">
        <a-menu-item key="home">
          <HomeOutlined />
          <span>工作台</span>
        </a-menu-item>

        <a-menu-item key="shares" @click="router.push('/shares')">
          <ShareAltOutlined />
          <span>我的分享</span>
        </a-menu-item>

        <a-menu-item key="graph" @click="router.push('/graph')">
          <ApartmentOutlined />
          <span>知识图谱</span>
        </a-menu-item>

        <a-menu-item key="trash" @click="router.push('/trash')">
          <DeleteOutlined />
          <span>回收站</span>
        </a-menu-item>

        <a-menu-item v-if="isAdminUser" key="admin" @click="router.push('/admin')">
          <TeamOutlined />
          <span>管理员中心</span>
        </a-menu-item>

        <a-sub-menu key="notebooks">
          <template #title>
            <span>
              <BookOutlined />
              <span>知识库</span>
            </span>
          </template>

          <a-menu-item
            v-for="notebook in notebookStore.notebooks"
            :key="`nb-${notebook.id}`"
            class="notebook-menu-item"
          >
            <div class="notebook-item-row" @click="router.push(`/notebook/${notebook.id}`)">
              <span class="notebook-name">{{ notebook.name }}</span>
              <a-dropdown :trigger="['click']">
                <a-button type="text" size="small" class="notebook-more-btn" @click.stop>
                  <SettingOutlined />
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item key="edit" @click.stop="showEditNotebookModal(notebook)">
                      编辑笔记本
                    </a-menu-item>
                    <a-menu-item
                      key="delete"
                      style="color: #ff4d4f"
                      @click.stop="handleDeleteNotebook(notebook.id)"
                    >
                      删除笔记本
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </a-menu-item>
        </a-sub-menu>

        <a-menu-item key="settings" @click="openProfileModal">
          <SettingOutlined />
          <span>个人中心</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header class="layout-header">
        <div class="header-left">
          <MenuUnfoldOutlined v-if="collapsed" class="trigger" @click="collapsed = !collapsed" />
          <MenuFoldOutlined v-else class="trigger" @click="collapsed = !collapsed" />

          <div class="search-box">
            <a-popover
              v-model:open="isSearching"
              placement="bottom"
              trigger="click"
              overlayClassName="search-popover"
            >
              <template #content>
                <div class="search-popover-panel">
                  <div class="search-filters">
                    <a-select
                      v-model:value="searchFilters.notebookId"
                      allow-clear
                      class="search-filter"
                      placeholder="全部笔记本"
                      :options="notebookOptions"
                      @change="applySearchFilters"
                    />
                    <a-select
                      v-model:value="searchFilters.tagName"
                      allow-clear
                      class="search-filter"
                      placeholder="全部标签"
                      :options="tagOptions"
                      @change="applySearchFilters"
                    />
                    <div class="date-filter-field">
                      <span class="date-filter-label">开始日期</span>
                      <a-date-picker
                        v-model:value="searchFilters.startDate"
                        class="date-filter"
                        format="YYYY年MM月DD日"
                        value-format="YYYY-MM-DD"
                        placeholder="选择开始日期"
                        @change="applySearchFilters"
                      />
                    </div>
                    <div class="date-filter-field">
                      <span class="date-filter-label">结束日期</span>
                      <a-date-picker
                        v-model:value="searchFilters.endDate"
                        class="date-filter"
                        format="YYYY年MM月DD日"
                        value-format="YYYY-MM-DD"
                        placeholder="选择结束日期"
                        @change="applySearchFilters"
                      />
                    </div>
                  </div>

                  <div class="search-filter-summary">
                    <div class="active-filters">
                      <a-tag v-for="label in activeFilterTags" :key="label" color="blue">
                        {{ label }}
                      </a-tag>
                      <span v-if="activeFilterCount === 0" class="filter-placeholder">当前未启用筛选条件</span>
                    </div>
                    <a-button size="small" @click="resetSearchFilters" :disabled="activeFilterCount === 0">
                      重置筛选
                    </a-button>
                  </div>

                  <div v-if="searchQuery.trim() && !hasInvalidDateRange" class="search-result-meta">
                    共找到 {{ searchResults.length }} 条结果，已自动记住本次搜索条件
                  </div>

                  <div v-if="hasInvalidDateRange" class="search-empty search-error">
                    开始日期不能晚于结束日期
                  </div>
                  <div v-else-if="searchQuery.trim() && searchResults.length > 0" class="search-results">
                    <div v-for="group in groupedSearchResults" :key="group.key" class="search-group">
                      <div class="search-group-head">
                        <span class="search-group-title">{{ group.notebookName }}</span>
                        <span class="search-group-count">{{ group.count }} 条</span>
                      </div>
                      <div
                        v-for="item in group.items"
                        :key="item.note.id"
                        class="search-item"
                        @click="openNote(item.note)"
                      >
                        <div class="search-item-title" v-html="item.titleHtml"></div>
                        <div class="search-item-meta">
                          <span>{{ item.updatedLabel }}</span>
                          <span>笔记本：{{ item.notebookName }}</span>
                        </div>
                        <div class="search-item-desc" v-html="item.excerptHtml"></div>
                        <div v-if="item.matchedTags.length > 0" class="search-item-tags">
                          <a-tag v-for="tag in item.matchedTags" :key="`${item.note.id}-${tag}`" color="blue">
                            #{{ tag }}
                          </a-tag>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div v-else-if="searchQuery.trim()" class="search-empty">没有符合条件的结果</div>
                  <div v-else class="search-empty">输入关键词开始搜索，可同时按笔记本、标签和日期过滤</div>
                </div>
              </template>

              <a-input
                v-model:value="searchQuery"
                placeholder="搜索知识库..."
                style="width: 340px"
                @focus="isSearching = true"
                @input="onSearchInput"
              >
                <template #prefix><SearchOutlined style="color: rgba(0, 0, 0, 0.25)" /></template>
                <template #suffix>
                  <span v-if="activeFilterCount > 0" class="filter-count">{{ activeFilterCount }}</span>
                </template>
              </a-input>
            </a-popover>
          </div>
        </div>

        <div class="header-right">
          <a-button type="default" shape="round" @click="aiDrawerVisible = true">
            <template #icon><RobotOutlined /></template>
            AI 问答
          </a-button>

          <a-button type="default" shape="round" @click="handleImport">
            <template #icon><ImportOutlined /></template>
            导入
          </a-button>

          <a-button type="primary" shape="round" @click="showCreateModal">
            <template #icon><PlusOutlined /></template>
            新建笔记本
          </a-button>

          <a-dropdown>
            <span class="user-dropdown">
              <a-avatar class="user-avatar">{{ avatarText }}</a-avatar>
              <span class="username">{{ displayName }}</span>
            </span>

            <template #overlay>
              <a-menu>
                <a-menu-item v-if="isAdminUser" @click="router.push('/admin')">管理员中心</a-menu-item>
                <a-menu-item @click="openProfileModal">个人中心</a-menu-item>
                <a-menu-item @click="handleLogout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>

      <a-layout-content class="layout-content">
        <DashboardView />

        <div class="recent-card">
          <h2>最近编辑</h2>
          <a-list item-layout="horizontal" :data-source="recentNotes">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta :description="new Date(item.updatedAt).toLocaleString('zh-CN')">
                  <template #title>
                    <a @click.prevent="openNote(item)">{{ item.title || '无标题笔记' }}</a>
                  </template>
                  <template #avatar>
                    <a-avatar shape="square" class="recent-avatar">
                      <BookOutlined />
                    </a-avatar>
                  </template>
                </a-list-item-meta>
              </a-list-item>
            </template>
          </a-list>
        </div>
      </a-layout-content>
    </a-layout>

    <a-modal
      v-model:open="isCreateModalVisible"
      title="新建笔记本"
      ok-text="创建"
      cancel-text="取消"
      @ok="handleCreateNotebook"
    >
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="createForm.name" placeholder="请输入笔记本名称" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="createForm.description" placeholder="请输入笔记本描述" />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="createForm.isPublic">设为公开</a-checkbox>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="isEditModalVisible"
      title="编辑笔记本"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleEditNotebook"
    >
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="createForm.name" placeholder="请输入笔记本名称" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="createForm.description" placeholder="请输入笔记本描述" />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="createForm.isPublic">设为公开</a-checkbox>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="profileModalVisible"
      title="个人中心"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="profileSaving"
      @ok="handleSaveProfile"
    >
      <a-spin :spinning="profileLoading">
        <a-form :model="profileForm" layout="vertical">
          <a-form-item label="用户名">
            <a-input :value="profileForm.username" disabled />
          </a-form-item>
          <a-form-item label="邮箱">
            <a-input :value="profileForm.email" disabled />
          </a-form-item>
          <a-form-item label="昵称">
            <a-input v-model:value="profileForm.nickname" maxlength="50" placeholder="请输入昵称" />
          </a-form-item>
          <a-form-item label="绑定手机号">
            <a-input v-model:value="profileForm.phone" maxlength="20" placeholder="请输入手机号" />
          </a-form-item>
          <a-form-item label="生日">
            <a-input v-model:value="profileForm.birthday" type="date" />
          </a-form-item>
          <a-form-item label="简介">
            <a-textarea
              v-model:value="profileForm.bio"
              :rows="4"
              maxlength="500"
              placeholder="写一点个人介绍，让协作者更了解你"
            />
          </a-form-item>
        </a-form>
      </a-spin>
    </a-modal>

    <AIAssistantDrawer v-model:visible="aiDrawerVisible" />
    <ImportModal v-model:visible="importModalVisible" />
  </a-layout>
</template>

<style scoped>
.layout-container {
  min-height: 100vh;
}

.sider {
  padding: 18px 12px;
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  background: linear-gradient(180deg, #fbfaf8 0%, #f6f5f4 100%) !important;
}

.sider :deep(.ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.logo {
  min-height: 88px;
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-radius: 20px;
  font-size: 18px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.95);
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.logo img {
  height: 56px;
  width: 56px;
  object-fit: contain;
  padding: 4px;
  border-radius: 16px;
  background: #f6f5f4;
}

.layout-header {
  height: auto;
  margin: 20px 24px 0;
  padding: 18px 24px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 18px;
  box-shadow: var(--sn-shadow-card);
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trigger {
  font-size: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: rgba(255, 255, 255, 0.9);
  box-shadow: var(--sn-shadow-card);
  color: #615d59;
  cursor: pointer;
  transition: border-color 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.trigger:hover {
  color: #097fe8;
  border-color: rgba(0, 117, 222, 0.2);
  transform: translateY(-1px);
}

.search-box {
  width: min(420px, calc(100vw - 220px));
}

.search-box :deep(.ant-input-affix-wrapper) {
  min-height: 44px;
}

.search-popover-panel {
  width: min(480px, calc(100vw - 48px));
}

.search-filters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.search-filter,
.date-filter {
  width: 100%;
}

.date-filter-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.date-filter-label,
.filter-placeholder,
.search-result-meta,
.search-group-count,
.search-item-meta,
.search-item-desc {
  color: #615d59;
}

.date-filter-label,
.filter-placeholder,
.search-result-meta,
.search-group-count,
.search-item-meta {
  font-size: 12px;
}

.search-filter-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.active-filters,
.search-item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.search-results {
  max-height: 420px;
  overflow-y: auto;
}

.search-group + .search-group {
  margin-top: 16px;
}

.search-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
  padding-inline: 2px;
}

.search-group-title {
  color: rgba(0, 0, 0, 0.95);
  font-size: 14px;
  font-weight: 700;
}

.search-item {
  padding: 14px 12px;
  border-radius: 14px;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.2s ease;
}

.search-item:hover {
  background: #f6fafd;
  transform: translateY(-1px);
}

.search-item-title {
  color: rgba(0, 0, 0, 0.95);
  font-weight: 700;
  font-size: 15px;
}

.search-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
}

.search-item-desc {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
}

.search-item-title :deep(mark),
.search-item-desc :deep(mark) {
  padding: 0 3px;
  border-radius: 4px;
  background: #fff2d9;
  color: #b45309;
}

.search-empty {
  padding: 18px;
  text-align: center;
  color: #615d59;
  font-size: 13px;
}

.search-error {
  color: #d4380d;
}

.filter-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 9999px;
  background: #f2f9ff;
  color: #097fe8;
  font-size: 12px;
  font-weight: 700;
}

.user-dropdown {
  padding: 8px 12px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--sn-shadow-card);
  cursor: pointer;
}

.user-avatar,
.recent-avatar {
  background: #f2f9ff;
  color: #097fe8;
}

.username {
  color: rgba(0, 0, 0, 0.95);
  font-weight: 600;
}

.layout-content {
  margin: 12px 24px 32px;
  padding: 0;
  background: transparent;
  min-height: 280px;
}

.recent-card {
  margin-top: 20px;
  padding: 28px;
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 16px;
  box-shadow: var(--sn-shadow-card);
}

.recent-card h2 {
  margin: 0 0 16px;
  color: rgba(0, 0, 0, 0.95);
  font-size: 30px;
  line-height: 1.1;
  letter-spacing: -0.8px;
}

.notebook-menu-item {
  padding-right: 0;
}

.notebook-item-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.notebook-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.notebook-more-btn {
  visibility: hidden;
  color: #615d59;
}

.ant-menu-item:hover .notebook-more-btn {
  visibility: visible;
}

@media (max-width: 960px) {
  .layout-header,
  .layout-content {
    padding-inline: 16px;
  }

  .layout-header {
    margin-inline: 16px;
  }

  .search-popover-panel {
    width: min(420px, calc(100vw - 32px));
  }

  .search-filters {
    grid-template-columns: 1fr;
  }

  .search-item-meta {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 720px) {
  .layout-header {
    margin-top: 16px;
    flex-direction: column;
    align-items: stretch;
  }

  .header-left,
  .header-right {
    width: 100%;
    justify-content: space-between;
  }

  .search-box {
    width: 100%;
  }

  .recent-card {
    padding: 22px 18px;
  }
}
</style>
