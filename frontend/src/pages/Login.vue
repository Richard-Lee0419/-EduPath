<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { enterReview, getReviewAvailability, login } from '../api/auth';
import wordmarkUrl from '../assets/images/edupath-wordmark-white.png';
import AuthLearningVisual from '../components/AuthLearningVisual.vue';
import ExitButton from '../components/ExitButton.vue';
import type { User, PageKey, ReviewRole } from '../types';

defineProps<{ hasReviewLink?: boolean }>();

const emit = defineEmits<{
  authenticated: [user: User];
  navigate: [page: PageKey];
  teacherRegistration: [];
}>();

const username = ref('');
const password = ref('');
const errorMessage = ref('');
const errorField = ref<'username' | 'password' | 'form' | ''>('');
const isSubmitting = ref(false);
const showPassword = ref(false);
const reviewError = ref('');
const pendingReviewRole = ref<ReviewRole | null>(null);
const oneClickReview = ref(false);

onMounted(async () => {
  try {
    oneClickReview.value = (await getReviewAvailability()).enabled === true;
  } catch {
    oneClickReview.value = false;
  }
});

const openReview = async (role: ReviewRole) => {
  if (isSubmitting.value || pendingReviewRole.value) return;
  reviewError.value = '';
  pendingReviewRole.value = role;
  try {
    const user = await enterReview(role);
    emit('authenticated', user);
  } catch (error) {
    reviewError.value = error instanceof Error ? error.message : '评委体验暂时不可用，请稍后重试';
  } finally {
    pendingReviewRole.value = null;
  }
};

const submit = async () => {
  if (isSubmitting.value || pendingReviewRole.value) return;
  errorMessage.value = '';
  errorField.value = '';

  if (!username.value.trim()) {
    errorMessage.value = '请输入用户名';
    errorField.value = 'username';
    return;
  }

  if (!password.value.trim()) {
    errorMessage.value = '请输入密码';
    errorField.value = 'password';
    return;
  }

  isSubmitting.value = true;
  try {
    const user = await login({
      username: username.value.trim(),
      password: password.value,
    });
    emit('authenticated', user);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请稍后重试';
    errorField.value = 'form';
  } finally {
    isSubmitting.value = false;
  }
};
</script>

<template>
  <main class="auth-page auth-page--target auth-access-page page-enter">
    <AuthLearningVisual />

    <ExitButton
      class="auth-welcome-exit"
      target="welcome"
      title="返回欢迎页"
      label="返回欢迎页"
      @exit="emit('navigate', $event)"
    />

    <section class="auth-form-panel">
      <div class="auth-brand-lockup">
        <div class="auth-wordmark-frame">
          <img
            :src="wordmarkUrl"
            alt="知径 EduPath"
            class="auth-wordmark-image"
            width="1100"
            height="932"
            decoding="async"
            fetchpriority="high"
          />
        </div>
        <p>构建你的个性化学习路径，让学习画像随学习过程持续更新。</p>
      </div>

      <div class="auth-form-panel__header">
        <div class="auth-tabs" aria-label="登录注册切换">
          <button class="active" type="button">登录</button>
          <button type="button" @click="emit('navigate', 'register')">注册</button>
        </div>
      </div>

      <form
        class="auth-form"
        aria-label="账号登录"
        :aria-busy="isSubmitting"
        :aria-describedby="errorMessage ? 'login-error' : undefined"
        @submit.prevent="submit"
      >
        <label>
          <span>用户名或邮箱</span>
          <input
            v-model="username"
            autocomplete="username"
            name="username"
            placeholder="请输入用户名或邮箱"
            :aria-invalid="errorField === 'username'"
            :aria-describedby="errorField === 'username' ? 'login-error' : undefined"
          />
        </label>

        <label>
          <span>密码</span>
          <div class="password-field">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              name="password"
              placeholder="请输入密码"
              :aria-invalid="errorField === 'password'"
              :aria-describedby="errorField === 'password' ? 'login-error' : undefined"
            />
            <button
              class="password-toggle"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :aria-pressed="showPassword"
              @click="showPassword = !showPassword"
            >
              <span :class="{ visible: showPassword }"></span>
            </button>
          </div>
        </label>

        <p v-if="errorMessage" id="login-error" class="auth-error" role="alert">{{ errorMessage }}</p>

        <button class="primary-action wide" type="submit" :disabled="isSubmitting || pendingReviewRole !== null">
          {{ isSubmitting ? '正在进入' : '进入知径 EduPath  →' }}
        </button>
      </form>

      <section class="auth-review-card" aria-label="评委体验" :aria-busy="pendingReviewRole !== null">
        <div class="auth-review-card__intro">
          <span class="auth-review-card__icon" aria-hidden="true">✦</span>
          <div>
            <h2>评委体验</h2>
            <p>{{ hasReviewLink || oneClickReview
              ? '直接选择身份，体验示例班级、课程和 PDF 辅导。'
              : '请使用参赛材料中的评委专属链接。' }}</p>
          </div>
        </div>
        <div v-if="hasReviewLink || oneClickReview" class="auth-review-card__actions">
          <button type="button" :disabled="isSubmitting || pendingReviewRole !== null" @click="openReview('teacher')">
            {{ pendingReviewRole === 'teacher' ? '正在进入…' : '进入教师工作台 →' }}
          </button>
          <button type="button" :disabled="isSubmitting || pendingReviewRole !== null" @click="openReview('student')">
            {{ pendingReviewRole === 'student' ? '正在进入…' : '进入学生端 →' }}
          </button>
        </div>
        <p v-if="reviewError" class="auth-error" role="alert">{{ reviewError }}</p>
      </section>

      <div class="auth-switch auth-access-footer">
        <span>没有账号？</span>
        <button class="text-action" type="button" @click="emit('navigate', 'register')">立即注册</button>
        <button class="text-action" type="button" @click="emit('teacherRegistration')">教师邀请码</button>
      </div>
    </section>
  </main>
</template>
