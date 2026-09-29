<script setup lang="ts">
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Component } from 'vue';
import { getStoredUser, logout } from './api/auth';
import AppShell from './components/AppShell.vue';
import AsyncPageLoading from './components/AsyncPageLoading.vue';
import Dashboard from './pages/Dashboard.vue';
import LearningHome from './pages/LearningHome.vue';
import Login from './pages/Login.vue';
import Register from './pages/Register.vue';
import Welcome from './pages/Welcome.vue';
import type { User, PageKey } from './types';

const lazyPage = (loader: () => Promise<{ default: Component }>) => defineAsyncComponent({
  loader,
  loadingComponent: AsyncPageLoading,
  delay: 80,
  timeout: 30000,
});

const Evaluation = lazyPage(() => import('./pages/Evaluation.vue'));
const KnowledgeBase = lazyPage(() => import('./pages/KnowledgeBase.vue'));
const LearningPath = lazyPage(() => import('./pages/LearningPath.vue'));
const ProfileChat = lazyPage(() => import('./pages/ProfileChat.vue'));
const Quiz = lazyPage(() => import('./pages/Quiz.vue'));
const ResourceGenerate = lazyPage(() => import('./pages/ResourceGenerate.vue'));
const ResourceCenter = lazyPage(() => import('./pages/ResourceCenter.vue'));
const Tutor = lazyPage(() => import('./pages/Tutor.vue'));
const TeacherInsights = lazyPage(() => import('./pages/TeacherInsights.vue'));
const TaskCenter = lazyPage(() => import('./pages/TaskCenter.vue'));
const UserCenter = lazyPage(() => import('./pages/UserCenter.vue'));

const user = ref<User | null>(getStoredUser());
const lastUserCenterSource = ref<PageKey>('dashboard');
const initialProfileIntent = ref<{ text: string; nonce: number } | null>(null);

const pageRoutes: Record<PageKey, string> = {
  welcome: '/',
  login: '/login',
  register: '/register',
  dashboard: '/dashboard',
  learningHome: '/learning-home',
  profile: '/profile-chat',
  resources: '/resource-generate',
  resourceCenter: '/resources',
  learningPath: '/learning-path',
  knowledgeBase: '/knowledge-base',
  tutor: '/tutor',
  quiz: '/quiz',
  evaluation: '/evaluation',
  teacherInsights: '/teacher-insights',
  taskCenter: '/task-center',
  userCenter: '/user-center',
};

const pathToPage = new Map<string, PageKey>([
  ['/', 'welcome'],
  ['/dashboard', 'dashboard'],
  ['/login', 'login'],
  ['/register', 'register'],
  ['/learning-home', 'learningHome'],
  ['/profile', 'profile'],
  ['/profile-chat', 'profile'],
  ['/resource-generate', 'resources'],
  ['/resources', 'resourceCenter'],
  ['/learning-path', 'learningPath'],
  ['/knowledge-base', 'knowledgeBase'],
  ['/knowledge-upload', 'knowledgeBase'],
  ['/tutor', 'tutor'],
  ['/quiz', 'quiz'],
  ['/evaluation', 'evaluation'],
  ['/teacher-insights', 'teacherInsights'],
  ['/task-center', 'taskCenter'],
  ['/user-center', 'userCenter'],
]);

const pageFromPath = (path: string): PageKey => pathToPage.get(path) ?? 'dashboard';
const isPublicPage = (page: PageKey) => page === 'welcome' || page === 'login' || page === 'register';
const isAuthPage = (page: PageKey) => page === 'login' || page === 'register';
const canAccessPage = (page: PageKey) => page !== 'teacherInsights' || user.value?.role === 'teacher' || user.value?.role === 'admin';

const routeFromPage = (page: PageKey) => pageRoutes[page];

const scrollToPageTop = () => {
  void nextTick(() => {
    window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
  });
};

const replaceRoute = (page: PageKey) => {
  const target = routeFromPage(page);
  if (window.location.pathname !== target) {
    window.history.replaceState({ page }, '', target);
  }
};

const pushRoute = (page: PageKey) => {
  const target = routeFromPage(page);
  if (window.location.pathname !== target) {
    window.history.pushState({ page }, '', target);
  }
};

const resolveInitialPage = (): PageKey => {
  const routePage = pageFromPath(window.location.pathname);
  if (!user.value) {
    return isPublicPage(routePage) ? routePage : 'login';
  }
  if (!canAccessPage(routePage)) {
    return 'dashboard';
  }
  return isAuthPage(routePage) ? 'dashboard' : routePage;
};

const currentPage = ref<PageKey>(resolveInitialPage());
replaceRoute(currentPage.value);

const setCurrentPage = (page: PageKey, options: { replace?: boolean } = {}) => {
  currentPage.value = page;
  if (options.replace) {
    replaceRoute(page);
  } else {
    pushRoute(page);
  }
  scrollToPageTop();
};

const navigate = (page: PageKey) => {
  if (!user.value && !isPublicPage(page)) {
    setCurrentPage('login', { replace: true });
    return;
  }
  if (!canAccessPage(page)) {
    setCurrentPage('dashboard', { replace: true });
    return;
  }

  if (page === 'userCenter' && currentPage.value !== 'userCenter') {
    lastUserCenterSource.value =
      currentPage.value === 'login' || currentPage.value === 'register' ? 'dashboard' : currentPage.value;
  }

  setCurrentPage(page);
};

const handleExit = (target: PageKey = currentPage.value === 'userCenter' ? lastUserCenterSource.value : 'dashboard') => {
  navigate(target);
};

const handleOpenUserCenter = () => {
  navigate('userCenter');
};

const handleProfileIntent = (question: string) => {
  const text = question.trim();
  if (!text) return;
  initialProfileIntent.value = {
    text,
    nonce: Date.now(),
  };
  navigate('profile');
};

const clearProfileIntent = () => {
  initialProfileIntent.value = null;
};

const handleAuthenticated = (authenticatedUser: User) => {
  user.value = authenticatedUser;
  setCurrentPage('dashboard', { replace: true });
};

const handleLogout = async () => {
  await logout();
  user.value = null;
  setCurrentPage('login', { replace: true });
};

const handlePopState = () => {
  const routePage = pageFromPath(window.location.pathname);
  if (!user.value && !isPublicPage(routePage)) {
    setCurrentPage('login', { replace: true });
    return;
  }
  if (user.value && isAuthPage(routePage)) {
    setCurrentPage('dashboard', { replace: true });
    return;
  }
  if (!canAccessPage(routePage)) {
    setCurrentPage('dashboard', { replace: true });
    return;
  }
  currentPage.value = routePage;
};

onMounted(() => {
  window.addEventListener('popstate', handlePopState);
});

onBeforeUnmount(() => {
  window.removeEventListener('popstate', handlePopState);
});
</script>

<template>
  <Welcome
    v-if="currentPage === 'welcome'"
    :is-authenticated="Boolean(user)"
    @navigate="navigate"
  />
  <Register
    v-else-if="currentPage === 'register' && !user"
    @authenticated="handleAuthenticated"
    @navigate="navigate"
  />
  <Login v-else-if="!user" @authenticated="handleAuthenticated" @navigate="navigate" />

  <AppShell
    v-else
    :current-page="currentPage"
    :user="user"
    @navigate="navigate"
    @logout="handleLogout"
    @exit="handleExit"
    @open-user-center="handleOpenUserCenter"
  >
    <Dashboard
      v-if="currentPage === 'dashboard'"
      :user="user"
      @navigate="navigate"
      @logout="handleLogout"
      @profile-intent="handleProfileIntent"
    />
    <LearningHome v-else-if="currentPage === 'learningHome'" @navigate="navigate" />
    <ProfileChat
      v-else-if="currentPage === 'profile'"
      :initial-intent="initialProfileIntent"
      @initial-intent-consumed="clearProfileIntent"
      @navigate="navigate"
    />
    <ResourceGenerate v-else-if="currentPage === 'resources'" @navigate="navigate" />
    <ResourceCenter v-else-if="currentPage === 'resourceCenter'" />
    <LearningPath v-else-if="currentPage === 'learningPath'" @navigate="navigate" />
    <KnowledgeBase v-else-if="currentPage === 'knowledgeBase'" :user="user" />
    <Tutor v-else-if="currentPage === 'tutor'" @navigate="navigate" />
    <Quiz v-else-if="currentPage === 'quiz'" />
    <Evaluation v-else-if="currentPage === 'evaluation'" @navigate="navigate" />
    <TeacherInsights v-else-if="currentPage === 'teacherInsights'" />
    <TaskCenter v-else-if="currentPage === 'taskCenter'" />
    <UserCenter v-else :user="user" @navigate="navigate" @logout="handleLogout" @exit="handleExit()" />
  </AppShell>
</template>
