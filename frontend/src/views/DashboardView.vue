<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import api from '../api';
import { FileTextOutlined, BookOutlined, TagOutlined, TeamOutlined } from '@ant-design/icons-vue';

const stats = ref<Record<string, number>>({});
const loading = ref(true);

onMounted(async () => {
  try {
    const response = await api.get('/stats/overview');
    stats.value = response.data;
  } catch (error) {
    console.error('Failed to fetch stats', error);
  } finally {
    loading.value = false;
  }
});

const workspaceCards = computed(() => ([
  {
    key: 'notes',
    label: '我的笔记',
    value: stats.value.totalNotes ?? 0,
    hint: '持续记录中的内容资产',
    icon: FileTextOutlined,
    tone: 'blue',
  },
  {
    key: 'notebooks',
    label: '我的笔记本',
    value: stats.value.totalNotebooks ?? 0,
    hint: '按主题整理知识空间',
    icon: BookOutlined,
    tone: 'gold',
  },
  {
    key: 'tags',
    label: '我的标签',
    value: stats.value.totalTags ?? 0,
    hint: '帮助检索和建立连接',
    icon: TagOutlined,
    tone: 'teal',
  },
]));

const adminCards = computed(() => (
  stats.value.sysTotalUsers === undefined
    ? []
    : [
        {
          key: 'users',
          label: '系统用户',
          value: stats.value.sysTotalUsers ?? 0,
          hint: '平台中的注册账号总量',
          icon: TeamOutlined,
          tone: 'orange',
        },
        {
          key: 'sysNotes',
          label: '系统笔记',
          value: stats.value.sysTotalNotes ?? 0,
          hint: '全站知识内容规模',
          icon: FileTextOutlined,
          tone: 'blue',
        },
        {
          key: 'sysNotebooks',
          label: '系统笔记本',
          value: stats.value.sysTotalNotebooks ?? 0,
          hint: '被组织管理的主题容器',
          icon: BookOutlined,
          tone: 'gold',
        },
        {
          key: 'sysTags',
          label: '系统标签',
          value: stats.value.sysTotalTags ?? 0,
          hint: '沉淀出来的索引关键词',
          icon: TagOutlined,
          tone: 'teal',
        },
      ]
));
</script>

<template>
  <section class="dashboard-shell">
    <div class="dashboard-intro surface-card">
      <div>
        <span class="intro-badge">Overview</span>
        <h2>今天的工作台，应该像一张安静而清晰的纸。</h2>
        <p>
          这里聚合了你的笔记、笔记本和标签规模，也为后续的整理、分享与协作提供入口。
        </p>
      </div>
      <div class="intro-meta">
        <span>Warm neutral UI</span>
        <span>Focused writing flow</span>
      </div>
    </div>

    <div v-if="loading" class="loading-state surface-card">
      <a-spin size="large" />
    </div>

    <template v-else>
      <div class="dashboard-grid">
        <article
          v-for="card in workspaceCards"
          :key="card.key"
          class="metric-card dashboard-card"
          :class="`tone-${card.tone}`"
        >
          <div class="dashboard-card-icon">
            <component :is="card.icon" />
          </div>
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.hint }}</small>
        </article>
      </div>

      <section v-if="adminCards.length" class="admin-panel surface-card">
        <div class="panel-head">
          <div>
            <span class="panel-kicker">Admin Snapshot</span>
            <h3>系统概览</h3>
            <p>如果当前账号拥有管理员权限，这里会补充平台级别的关键指标。</p>
          </div>
        </div>

        <div class="dashboard-grid admin-grid">
          <article
            v-for="card in adminCards"
            :key="card.key"
            class="metric-card dashboard-card"
            :class="`tone-${card.tone}`"
          >
            <div class="dashboard-card-icon">
              <component :is="card.icon" />
            </div>
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.hint }}</small>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.dashboard-shell {
  display: grid;
  gap: 20px;
}

.dashboard-intro {
  padding: 28px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  background:
    radial-gradient(circle at top right, rgba(0, 117, 222, 0.08), transparent 22%),
    linear-gradient(180deg, #ffffff 0%, #fbfaf8 100%);
}

.intro-badge,
.panel-kicker {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 10px;
  border-radius: 9999px;
  background: #f2f9ff;
  color: #097fe8;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.dashboard-intro h2,
.panel-head h3 {
  margin: 14px 0 10px;
  font-size: clamp(26px, 3vw, 40px);
  font-weight: 700;
  line-height: 1.08;
  letter-spacing: -1px;
  color: rgba(0, 0, 0, 0.95);
}

.dashboard-intro p,
.panel-head p {
  margin: 0;
  max-width: 720px;
  color: #615d59;
  line-height: 1.7;
}

.intro-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  color: #a39e98;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.dashboard-card {
  position: relative;
  overflow: hidden;
}

.dashboard-card::after {
  content: '';
  position: absolute;
  inset: auto 18px 0;
  height: 1px;
  background: rgba(0, 0, 0, 0.04);
}

.dashboard-card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 16px;
  font-size: 20px;
}

.tone-blue .dashboard-card-icon {
  background: rgba(0, 117, 222, 0.12);
  color: #0075de;
}

.tone-gold .dashboard-card-icon {
  background: rgba(221, 91, 0, 0.1);
  color: #dd5b00;
}

.tone-teal .dashboard-card-icon {
  background: rgba(42, 157, 153, 0.12);
  color: #2a9d99;
}

.tone-orange .dashboard-card-icon {
  background: rgba(82, 52, 16, 0.1);
  color: #523410;
}

.admin-panel {
  padding: 28px;
}

.loading-state {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 960px) {
  .dashboard-intro {
    flex-direction: column;
    align-items: flex-start;
  }

  .intro-meta {
    align-items: flex-start;
  }

  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
</style>
