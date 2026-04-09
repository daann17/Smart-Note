<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import { RobotOutlined } from '@ant-design/icons-vue';
import AIAssistantDrawer from './components/AIAssistantDrawer.vue';
import GlobalSearchModal from './components/GlobalSearchModal.vue';

const route = useRoute();
const aiDrawerVisible = ref(false);
const searchModalOpen = ref(false);

const showAIFab = computed(() => route.name !== 'login' && route.name !== 'share');

const handleGlobalKeydown = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    if (route.name === 'login' || route.name === 'share') {
      return;
    }

    event.preventDefault();
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
        colorPrimary: '#0075de',
        colorLink: '#0075de',
        colorInfo: '#0075de',
        colorSuccess: '#1aae39',
        colorWarning: '#dd5b00',
        colorError: '#d4380d',
        colorText: 'rgba(0, 0, 0, 0.95)',
        colorTextSecondary: '#615d59',
        colorTextPlaceholder: '#a39e98',
        colorBorder: 'rgba(0, 0, 0, 0.1)',
        colorBgLayout: '#f6f5f4',
        colorBgContainer: '#ffffff',
        colorFillAlter: '#fbfaf8',
        borderRadius: 12,
        fontFamily: 'Inter, Segoe UI, -apple-system, BlinkMacSystemFont, Helvetica Neue, sans-serif',
        boxShadow:
          'rgba(0,0,0,0.04) 0px 4px 18px, rgba(0,0,0,0.027) 0px 2.025px 7.84688px, rgba(0,0,0,0.02) 0px 0.8px 2.925px, rgba(0,0,0,0.01) 0px 0.175px 1.04062px',
        boxShadowSecondary:
          'rgba(0,0,0,0.01) 0px 1px 3px, rgba(0,0,0,0.02) 0px 3px 7px, rgba(0,0,0,0.02) 0px 7px 15px, rgba(0,0,0,0.04) 0px 14px 28px, rgba(0,0,0,0.05) 0px 23px 52px',
      },
    }"
  >
    <router-view />

    <button v-if="showAIFab" type="button" class="ai-fab" @click="aiDrawerVisible = true">
      <RobotOutlined class="ai-fab-icon" />
      <span class="ai-fab-text">AI</span>
    </button>

    <AIAssistantDrawer v-model:visible="aiDrawerVisible" />
    <GlobalSearchModal v-model:open="searchModalOpen" />
  </a-config-provider>
</template>

<style>
#app {
  min-height: 100vh;
}

.ai-fab {
  position: fixed;
  right: 28px;
  bottom: 28px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 16px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.96);
  color: #0075de;
  box-shadow:
    rgba(0, 0, 0, 0.04) 0 4px 18px,
    rgba(0, 0, 0, 0.027) 0 2.025px 7.84688px,
    rgba(0, 0, 0, 0.02) 0 0.8px 2.925px,
    rgba(0, 0, 0, 0.01) 0 0.175px 1.04062px;
  cursor: pointer;
  z-index: 999;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease;
}

.ai-fab:hover {
  transform: translateY(-2px);
  border-color: rgba(0, 117, 222, 0.24);
  background: #ffffff;
  box-shadow:
    rgba(0, 0, 0, 0.01) 0 1px 3px,
    rgba(0, 0, 0, 0.02) 0 3px 7px,
    rgba(0, 0, 0, 0.02) 0 7px 15px,
    rgba(0, 0, 0, 0.04) 0 14px 28px,
    rgba(0, 0, 0, 0.05) 0 23px 52px;
}

.ai-fab:focus-visible {
  outline: 2px solid #097fe8;
  outline-offset: 2px;
}

.ai-fab-icon {
  font-size: 20px;
}

.ai-fab-text {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

@media (max-width: 768px) {
  .ai-fab {
    right: 16px;
    bottom: 16px;
    padding-inline: 14px;
  }
}
</style>
