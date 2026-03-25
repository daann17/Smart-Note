import { createRouter, createWebHistory } from 'vue-router';
import HomeView from '../views/HomeView.vue';
import { getStoredRole } from '../utils/session';

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { requiresGuest: true },
    },
    {
      path: '/home',
      name: 'home',
      component: HomeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/shares',
      name: 'shares',
      component: () => import('../views/ShareManageView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/graph',
      name: 'graph',
      component: () => import('../views/KnowledgeGraphView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/trash',
      name: 'trash',
      component: () => import('../views/TrashView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('../views/AdminView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/notebook/:notebookId',
      name: 'notebook',
      component: () => import('../views/NoteView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/share/:token',
      name: 'share',
      component: () => import('../views/ShareView.vue'),
      meta: { requiresAuth: false },
    },
  ],
});

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token');
  const isAuthenticated = Boolean(token);
  const isAdmin = getStoredRole() === 'ADMIN';

  if (to.meta.requiresAuth && !isAuthenticated) {
    next({
      name: 'login',
      query: {
        redirect: to.fullPath,
      },
    });
    return;
  }

  if (to.meta.requiresAdmin && !isAdmin) {
    next({ name: 'home' });
    return;
  }

  if (to.meta.requiresGuest && isAuthenticated) {
    const redirect = typeof to.query.redirect === 'string' && to.query.redirect.startsWith('/')
      ? to.query.redirect
      : undefined;

    next(redirect || { name: 'home' });
    return;
  }

  next();
});

export default router;
