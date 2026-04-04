<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import AIAssistantDrawer from './components/AIAssistantDrawer.vue';
import GlobalSearchModal from './components/GlobalSearchModal.vue';
import { RobotOutlined } from '@ant-design/icons-vue';

const route = useRoute();
const aiDrawerVisible = ref(false);
const searchModalOpen = ref(false);

// 只有非登录页和公开分享页才显示 AI 助手按钮和搜索快捷键
const showAIFab = computed(() => {
  return route.name !== 'login' && route.name !== 'share';
});

// ──────────────────────────────────────────────────────────────────────────────
// 全局快捷键：Ctrl+K / ⌘+K 唤起全局搜索
// ──────────────────────────────────────────────────────────────────────────────
const handleGlobalKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    // 不在登录/分享页时才响应
    if (route.name === 'login' || route.name === 'share') return;
    e.preventDefault();
    searchModalOpen.value = true;
  }
};

onMounted(() => document.addEventListener('keydown', handleGlobalKeydown));
onBeforeUnmount(() => document.removeEventListener('keydown', handleGlobalKeydown));
</script>

<template>
  <a-config-provider
    :locale="zhCN"
    :theme="{
      token: {
        colorPrimary: '#1890ff', // 浅蓝色 (Ant Design v4 经典蓝，比较符合浅蓝定义)
        colorBgContainer: '#ffffff',
        colorBgLayout: '#f0f2f5', // 浅灰背景，突出白色卡片
        borderRadius: 4,
      },
    }"
  >
    <router-view />
    
    <!-- 全局悬浮 AI 按钮 -->
    <div v-if="showAIFab" class="ai-fab" @click="aiDrawerVisible = true">
      <RobotOutlined class="ai-fab-icon" />
    </div>

    <!-- AI 助手抽屉 -->
    <AIAssistantDrawer v-model:visible="aiDrawerVisible" />

    <!-- 全局搜索弹窗（Ctrl+K / ⌘+K 唤起） -->
    <GlobalSearchModal v-model:open="searchModalOpen" />
  </a-config-provider>
</template>

<style>
#app {
  height: 100vh;
  width: 100vw;
}

.ai-fab {
  position: fixed;
  bottom: 40px;
  right: 40px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #1890ff, #722ed1);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  cursor: pointer;
  z-index: 999;
  transition: transform 0.3s, box-shadow 0.3s;
}

.ai-fab:hover {
  transform: scale(1.1);
  box-shadow: 0 6px 16px rgba(0,0,0,0.2);
}

.ai-fab-icon {
  font-size: 28px;
}
</style>
