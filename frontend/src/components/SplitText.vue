<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    text: string;
    tag?: 'h1' | 'h2' | 'h3' | 'p' | 'span';
    delay?: number;
    stagger?: number;
  }>(),
  {
    tag: 'span',
    delay: 0,
    stagger: 44,
  },
);

const characters = computed(() => Array.from(props.text));
</script>

<template>
  <component :is="props.tag" class="split-text" :aria-label="props.text">
    <span
      v-for="(character, index) in characters"
      :key="`${props.text}-${character}-${index}`"
      class="split-text__character"
      :style="{
        '--split-index': index,
        '--split-delay': `${props.delay}ms`,
        '--split-stagger': `${props.stagger}ms`,
      }"
      aria-hidden="true"
    >{{ character === ' ' ? '\u00a0' : character }}</span>
  </component>
</template>

<style scoped>
.split-text {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
}

.split-text__character {
  display: inline-block;
  flex: 0 0 auto;
  opacity: 0;
  transform: translateY(0.34em);
  animation: splitTextCharacterReveal 460ms cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: calc(var(--split-delay) + var(--split-index) * var(--split-stagger));
  will-change: opacity, transform;
}

@keyframes splitTextCharacterReveal {
  from {
    opacity: 0;
    transform: translateY(0.34em);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .split-text__character {
    opacity: 1;
    transform: none;
    animation: none;
  }
}
</style>
