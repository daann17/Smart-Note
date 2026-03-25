<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { UserOutlined, LockOutlined, MailOutlined } from '@ant-design/icons-vue';
import { message } from 'ant-design-vue';
import { useRoute, useRouter } from 'vue-router';
import axios from 'axios';
import { storeSession } from '../utils/session';

type AuthMode = 'login' | 'register';

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const activeTab = ref<AuthMode>('login');
const formState = reactive({
  username: '',
  password: '',
  email: '',
});

const authApi = axios.create({
  baseURL: '/api',
});

const isLogin = computed(() => activeTab.value === 'login');
const redirectTarget = computed(() => {
  const redirect = route.query.redirect;
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/home';
});

const handleFinish = async (values: typeof formState) => {
  loading.value = true;

  try {
    if (isLogin.value) {
      const response = await authApi.post('/auth/login', {
        username: values.username,
        password: values.password,
      });

      const { token, username, displayName, role } = response.data;
      storeSession({
        token,
        username,
        displayName,
        role,
      });

      message.success('登录成功');
      router.push(redirectTarget.value);
      return;
    }

    await authApi.post('/auth/register', {
      username: values.username,
      password: values.password,
      email: values.email,
    });

    message.success('注册成功，请登录');
    activeTab.value = 'login';
    formState.password = '';
  } catch (error: any) {
    console.error('Auth error:', error);
    message.error(error.response?.data?.message || error.response?.data || '操作失败');
  } finally {
    loading.value = false;
  }
};

const handleFinishFailed = (errors: unknown) => {
  console.log('Failed:', errors);
};

const toggleMode = () => {
  activeTab.value = isLogin.value ? 'register' : 'login';
  formState.username = '';
  formState.password = '';
  formState.email = '';
};
</script>

<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="logo-area">
        <img src="/home-logo.png" alt="Logo" class="logo" />
        <h1 class="title">SmartNote</h1>
        <p class="subtitle">记录想法、沉淀知识、协同创作</p>
      </div>

      <a-tabs v-model:activeKey="activeTab" centered class="auth-tabs">
        <a-tab-pane key="login" tab="登录" />
        <a-tab-pane key="register" tab="注册" />
      </a-tabs>

      <a-form
        :model="formState"
        name="auth_form"
        class="auth-form"
        @finish="handleFinish"
        @finishFailed="handleFinishFailed"
      >
        <a-form-item
          name="username"
          :rules="[{ required: true, message: '请输入用户名' }]"
        >
          <a-input v-model:value="formState.username" placeholder="用户名" size="large">
            <template #prefix><UserOutlined style="color: rgba(0, 0, 0, 0.25)" /></template>
          </a-input>
        </a-form-item>

        <a-form-item
          v-if="!isLogin"
          name="email"
          :rules="[{ required: true, type: 'email', message: '请输入有效的邮箱地址' }]"
        >
          <a-input v-model:value="formState.email" placeholder="邮箱" size="large">
            <template #prefix><MailOutlined style="color: rgba(0, 0, 0, 0.25)" /></template>
          </a-input>
        </a-form-item>

        <a-form-item
          name="password"
          :rules="[{ required: true, message: '请输入密码' }]"
        >
          <a-input-password v-model:value="formState.password" placeholder="密码" size="large">
            <template #prefix><LockOutlined style="color: rgba(0, 0, 0, 0.25)" /></template>
          </a-input-password>
        </a-form-item>

        <a-form-item>
          <a-button type="primary" html-type="submit" block size="large" :loading="loading">
            {{ isLogin ? '登录' : '注册' }}
          </a-button>
        </a-form-item>
      </a-form>

      <div class="auth-footer">
        <a v-if="isLogin" @click.prevent="toggleMode">没有账号？立即注册</a>
        <a v-else @click.prevent="toggleMode">已有账号？立即登录</a>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: #f0f2f5;
  background-image: url('https://gw.alipayobjects.com/zos/rmsportal/TVYTbAXWheQpRcWDaDMu.svg');
  background-repeat: no-repeat;
  background-position: center 110px;
  background-size: 100%;
}

.auth-card {
  width: 400px;
  padding: 32px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.logo-area {
  text-align: center;
  margin-bottom: 24px;
}

.logo {
  height: 160px;
  margin-bottom: 16px;
}

.title {
  font-size: 24px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 8px;
}

.subtitle {
  color: rgba(0, 0, 0, 0.45);
  font-size: 14px;
  margin-bottom: 24px;
}

.auth-tabs {
  margin-bottom: 24px;
}

.auth-footer {
  text-align: center;
  margin-top: 16px;
}
</style>
