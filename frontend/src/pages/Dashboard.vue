<script setup lang="ts">
import { ref } from 'vue';
import ExitButton from '../components/ExitButton.vue';
import FeatureConstellation from '../components/FeatureConstellation.vue';
import HomeHeroIllustration from '../components/HomeHeroIllustration.vue';
import type { User, PageKey } from '../types';

defineProps<{
  user: User;
}>();

const emit = defineEmits<{
  navigate: [page: PageKey];
  logout: [];
  profileIntent: [question: string];
}>();

const searchIntent = ref('');
const heroGreeting = '嗨，同学！今天想学什么';
const heroGreetingCharacters = Array.from(heroGreeting);

const gatewayCards = [
  {
    title: '个人中心',
    subtitle: 'Personal Center',
    page: 'userCenter' as PageKey,
    tone: 'teal',
    icon: 'cap',
  },
  {
    title: '学习首页',
    subtitle: 'Learning Home',
    page: 'learningHome' as PageKey,
    tone: 'amber',
    icon: 'book',
  },
];

const submitSearch = () => {
  const text = searchIntent.value.trim();
  if (!text) return;
  emit('profileIntent', text);
};
</script>

<template>
  <div class="home-gateway page-enter">
    <ExitButton target="login" title="退出到登录页" @exit="$emit('logout')" />

    <section class="home-gateway-hero">
      <div class="home-learner-illustration" aria-hidden="true">
        <HomeHeroIllustration />
      </div>

      <div class="home-gateway-copy">
        <h1 class="home-gateway-title" :aria-label="heroGreeting">
          <span
            v-for="(character, index) in heroGreetingCharacters"
            :key="`${character}-${index}`"
            class="home-gateway-title__character"
            :style="{ '--character-index': index }"
            aria-hidden="true"
          >{{ character }}</span>
        </h1>
        <p>与您的专属 AI 智能体协作，构建个性化路径，生成专属资源包，并实时评估学习效果。</p>
      </div>

      <form class="home-search" @submit.prevent="submitSearch">
        <span class="home-search__spark" aria-hidden="true">✦</span>
        <input v-model="searchIntent" placeholder="咨询知径..." aria-label="输入学习目标" />
        <button class="primary-action" type="submit">搜索</button>
      </form>
    </section>

    <FeatureConstellation @navigate="$emit('navigate', $event)" />

    <section class="home-next-step" aria-label="今日学习入口">
      <div class="home-next-step__intro">
        <span>YOUR NEXT STEP</span>
        <h2>今天，从一个小目标开始</h2>
        <p>根据你的学习状态，选择一个入口继续构建画像、生成资源或检验掌握度。</p>
      </div>
      <div class="home-next-step__actions">
        <button type="button" class="home-next-step__action is-primary" @click="$emit('navigate', 'profile')">
          <strong>补充学习画像</strong><small>让推荐更贴合你的目标</small><i aria-hidden="true">→</i>
        </button>
        <button type="button" class="home-next-step__action" @click="$emit('navigate', 'resourceCenter')">
          <strong>浏览资源包</strong><small>按类型查看已生成内容</small><i aria-hidden="true">→</i>
        </button>
        <button type="button" class="home-next-step__action" @click="$emit('navigate', 'quiz')">
          <strong>做一次练习</strong><small>用结果更新学习反馈</small><i aria-hidden="true">→</i>
        </button>
      </div>
    </section>

    <section class="home-gateway-cards" aria-label="快捷入口">
      <button
        v-for="card in gatewayCards"
        :key="card.title"
        class="home-gateway-card interactive-card"
        :class="`home-gateway-card--${card.tone}`"
        type="button"
        @click="$emit('navigate', card.page)"
      >
        <span class="home-gateway-card__icon" :class="`home-gateway-card__icon--${card.icon}`" aria-hidden="true">
          <svg v-if="card.icon === 'cap'" viewBox="0 0 64 64">
            <path d="M8 25 32 13l24 12-24 13L8 25Z" />
            <path d="M19 34v10c8 6 18 6 26 0V34" />
            <path d="M53 28v15" />
            <circle cx="53" cy="47" r="3" />
          </svg>
          <svg v-else viewBox="0 0 64 64">
            <path d="M12 15c9-4 16-2 20 4v31c-5-6-12-8-20-4V15Z" />
            <path d="M32 19c4-6 11-8 20-4v31c-8-4-15-2-20 4V19Z" />
            <path d="M18 25h8M18 32h9M38 25h8M38 32h9" />
          </svg>
        </span>
        <span>
          <strong>{{ card.title }}</strong>
          <small>{{ card.subtitle }}</small>
        </span>
        <i aria-hidden="true">›</i>
      </button>
    </section>
  </div>
</template>
