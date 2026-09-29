<script setup lang="ts">
import { ref } from 'vue';
import { register } from '../api/auth';
import AuthLearningVisual from '../components/AuthLearningVisual.vue';
import EduPathLogo from '../components/EduPathLogo.vue';
import ExitButton from '../components/ExitButton.vue';
import type { User, PageKey } from '../types';

const emit = defineEmits<{
  authenticated: [user: User];
  navigate: [page: PageKey];
}>();

const email = ref('');
const password = ref('');
const confirmPassword = ref('');
const errorMessage = ref('');
const isSubmitting = ref(false);
const showPassword = ref(false);
const showConfirmPassword = ref(false);

const submit = async () => {
  errorMessage.value = '';

  const normalizedEmail = email.value.trim();
  if (!normalizedEmail) {
    errorMessage.value = '请输入邮箱地址';
    return;
  }

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail)) {
    errorMessage.value = '请输入有效的邮箱地址';
    return;
  }

  if (!password.value.trim()) {
    errorMessage.value = '请输入密码';
    return;
  }

  if (password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致';
    return;
  }

  isSubmitting.value = true;
  try {
    const user = await register({
      email: normalizedEmail,
      password: password.value,
    });
    emit('authenticated', user);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '注册失败，请稍后重试';
  } finally {
    isSubmitting.value = false;
  }
};
</script>

<template>
  <main class="auth-page auth-page--target page-enter">
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
        <div>
          <h1>知径 EduPath</h1>
          <p>创建学习账号，让 AI 智能体围绕你的目标生成路径、资源和评估闭环。</p>
        </div>
        <EduPathLogo />
      </div>

      <div class="auth-form-panel__header">
        <div class="auth-tabs" aria-label="登录注册切换">
          <button type="button" @click="emit('navigate', 'login')">登录</button>
          <button class="active" type="button">注册</button>
        </div>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input v-model="email" type="email" autocomplete="email" inputmode="email" placeholder="例如 name@qq.com" />
        </label>

        <label>
          <span>密码</span>
          <div class="password-field">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="设置登录密码"
            />
            <button
              class="password-toggle"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <span :class="{ visible: showPassword }"></span>
            </button>
          </div>
        </label>

        <label>
          <span>确认密码</span>
          <div class="password-field">
            <input
              v-model="confirmPassword"
              :type="showConfirmPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="再次输入密码"
            />
            <button
              class="password-toggle"
              type="button"
              :aria-label="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
              @click="showConfirmPassword = !showConfirmPassword"
            >
              <span :class="{ visible: showConfirmPassword }"></span>
            </button>
          </div>
        </label>

        <p v-if="errorMessage" class="auth-error">{{ errorMessage }}</p>

        <button class="primary-action wide" type="submit" :disabled="isSubmitting">
          {{ isSubmitting ? '正在创建' : '进入知径 EduPath  →' }}
        </button>
      </form>

      <div class="auth-switch">
        <span>已经有账号？</span>
        <button class="text-action" type="button" @click="emit('navigate', 'login')">立即登录</button>
      </div>

      <p class="auth-quote">“你的学习画像会随着学习持续进化。”</p>
    </section>
  </main>
</template>
