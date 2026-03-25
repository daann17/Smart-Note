import axios from 'axios';
import { message } from 'ant-design-vue';
import { clearSession } from '../utils/session';

const api = axios.create({
  baseURL: '/api',
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      clearSession();
      const redirect = `${window.location.pathname}${window.location.search}${window.location.hash}`;
      window.location.href = `/?redirect=${encodeURIComponent(redirect)}`;
    } else {
      message.error(error.response?.data?.message || '请求失败');
    }
    return Promise.reject(error);
  },
);

export default api;
