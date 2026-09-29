<script setup lang="ts">
import type { PageKey } from '../types';

interface FeatureBubbleItem {
  key: string;
  title: string;
  label: string;
  description: string;
  agents: string[];
  scenario: string;
  page?: PageKey;
  tone: 'primary' | 'resource' | 'weak' | 'data' | 'violet';
  position: {
    x: number;
    y: number;
    size: number;
  };
  reserved?: boolean;
}

defineProps<{
  item: FeatureBubbleItem;
  active: boolean;
  dimmed: boolean;
}>();

defineEmits<{
  enter: [item: FeatureBubbleItem];
  hover: [key: string];
  leave: [];
}>();
</script>

<template>
  <article
    class="feature-bubble"
    :class="[
      `feature-bubble--${item.tone}`,
      {
        active,
        dimmed,
        reserved: item.reserved,
        'feature-bubble--edge-left': item.position.x <= 18,
        'feature-bubble--edge-right': item.position.x >= 82,
        'feature-bubble--edge-bottom': item.position.y >= 66,
      },
    ]"
    :style="{
      '--bubble-x': `${item.position.x}%`,
      '--bubble-y': `${item.position.y}%`,
      '--bubble-size': `${item.position.size}px`,
    }"
    @mouseenter="$emit('hover', item.key)"
    @mouseleave="$emit('leave')"
  >
    <button
      class="feature-bubble__orb"
      type="button"
      :disabled="item.reserved"
      @click="$emit('enter', item)"
    >
      <span class="feature-bubble__icon" :class="`feature-bubble__icon--${item.key}`" aria-hidden="true">
        <svg v-if="item.key === 'profile'" viewBox="0 0 64 64">
          <path d="M16 16h32a8 8 0 0 1 8 8v14a8 8 0 0 1-8 8H31L19 56v-10h-3a8 8 0 0 1-8-8V24a8 8 0 0 1 8-8Z" />
        </svg>
        <svg v-else-if="item.key === 'resources'" viewBox="0 0 64 64">
          <path d="M8 20h19l5 7h24v25a6 6 0 0 1-6 6H14a6 6 0 0 1-6-6V20Z" />
          <path d="M42 36v6M42 50v6M32 46h6M46 46h6" />
          <circle cx="42" cy="46" r="8" />
        </svg>
        <svg v-else-if="item.key === 'learningPath'" viewBox="0 0 64 64">
          <path d="M19 10v25c0 8 6 14 14 14h12" />
          <path d="M45 18v36" />
          <circle cx="19" cy="10" r="6" />
          <circle cx="19" cy="35" r="6" />
          <circle cx="45" cy="18" r="6" />
          <circle cx="45" cy="54" r="6" />
        </svg>
        <svg v-else-if="item.key === 'knowledgeBase'" viewBox="0 0 64 64">
          <path d="M18 8h24l10 10v38H18V8Z" />
          <path d="M42 8v12h10M26 27h16M26 36h20M26 45h13" />
        </svg>
        <svg v-else-if="item.key === 'quiz'" viewBox="0 0 64 64">
          <path d="M12 52 15.5 38.5 43 11a6 6 0 0 1 8.5 8.5L24 47 12 52Z" />
          <path d="m36 18 10 10M15.5 38.5 24 47" />
        </svg>
        <svg v-else-if="item.key === 'evaluation'" viewBox="0 0 64 64">
          <path d="M14 10h36v44H14V10Z" />
          <path d="M24 43V31M33 43V23M42 43V35" />
        </svg>
        <svg v-else viewBox="0 0 64 64">
          <path d="M18 25h28a10 10 0 0 1 10 10v8a10 10 0 0 1-10 10H18A10 10 0 0 1 8 43v-8a10 10 0 0 1 10-10Z" />
          <path d="M24 25V13M40 25V13" />
          <circle cx="24" cy="39" r="3" />
          <circle cx="40" cy="39" r="3" />
          <path d="M29 47h6" />
        </svg>
      </span>
      <span>{{ item.label }}</span>
      <strong>{{ item.title }}</strong>
    </button>

    <div class="feature-bubble__detail">
      <h3>{{ item.title }}</h3>
      <p>{{ item.description }}</p>
      <div class="feature-bubble__meta">
        <small>关联 Agent</small>
        <strong>{{ item.agents.join(' / ') }}</strong>
      </div>
      <div class="feature-bubble__meta">
        <small>推荐场景</small>
        <strong>{{ item.scenario }}</strong>
      </div>
      <button class="primary-action" type="button" :disabled="item.reserved" @click="$emit('enter', item)">
        {{ item.reserved ? '预留中' : '进入功能' }}
      </button>
    </div>
  </article>
</template>
