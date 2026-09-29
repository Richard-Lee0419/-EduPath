<script setup lang="ts">
import type { User, PageKey } from '../types';
import ExitButton from './ExitButton.vue';
import UserAvatarButton from './UserAvatarButton.vue';

type FunctionalPageKey = Extract<
  PageKey,
  'learningHome' | 'profile' | 'resources' | 'resourceCenter' | 'learningPath' | 'knowledgeBase' | 'tutor' | 'quiz' | 'evaluation' | 'teacherInsights'
>;

defineProps<{
  currentPage: PageKey;
  user: User;
}>();

defineEmits<{
  navigate: [page: PageKey];
  exit: [target: PageKey];
  openUserCenter: [];
}>();

const baseNavItems: Array<{ key: FunctionalPageKey; label: string }> = [
  { key: 'learningHome', label: '学习首页' },
  { key: 'profile', label: '对话画像' },
  { key: 'resources', label: '资源生成' },
  { key: 'resourceCenter', label: '资源中心' },
  { key: 'learningPath', label: '学习路径' },
  { key: 'knowledgeBase', label: '知识库' },
  { key: 'tutor', label: '智能辅导' },
  { key: 'quiz', label: '练习测试' },
  { key: 'evaluation', label: '学习评估' },
];

const navItemsForUser = (user: User) => user.role === 'teacher' || user.role === 'admin'
  ? [...baseNavItems, { key: 'teacherInsights' as const, label: '教师工作台' }]
  : baseNavItems;
</script>

<template>
  <header class="app-top-nav" aria-label="功能页导航">
    <div class="app-top-nav__left">
      <ExitButton target="dashboard" title="返回首页" @exit="$emit('exit', $event)" />
      <button class="app-top-nav__brand nav-item-hover" type="button" @click="$emit('navigate', 'dashboard')">
        <strong>知径 EduPath</strong>
      </button>
    </div>

    <nav class="app-top-nav__links" aria-label="学习功能导航">
      <button
        v-for="item in navItemsForUser(user)"
        :key="item.key"
        class="app-top-nav__item nav-item-hover"
        :class="{ active: currentPage === item.key }"
        :aria-current="currentPage === item.key ? 'page' : undefined"
        type="button"
        @click="$emit('navigate', item.key)"
      >
        {{ item.label }}
      </button>
    </nav>

    <UserAvatarButton :user="user" @open="$emit('openUserCenter')" />
  </header>
</template>
