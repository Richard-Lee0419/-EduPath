<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/">
        <span class="brand-mark">E</span>
        <span>
          <strong>EduPath</strong>
          <small>多智能体学习系统</small>
        </span>
      </RouterLink>

      <nav class="nav-list" aria-label="主导航">
        <RouterLink v-for="item in navItems" :key="item.path" :to="item.path">
          {{ item.label }}
        </RouterLink>
      </nav>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <div>
          <p class="topbar-label">Software Cup A3</p>
          <h1>{{ route.meta.title ?? "EduPath" }}</h1>
        </div>
        <div class="service-pill">
          <span></span>
          API / AI Ready
        </div>
      </header>

      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from "vue-router";

const route = useRoute();

const navItems = [
  { path: "/", label: "演示总览" },
  { path: "/profile-chat", label: "画像对话" },
  { path: "/courses", label: "课程知识" },
  { path: "/resource-generate", label: "资源生成" },
  { path: "/learning-path", label: "学习路径" },
  { path: "/tutor", label: "智能辅导" },
  { path: "/quiz", label: "测试评估" },
];
</script>
