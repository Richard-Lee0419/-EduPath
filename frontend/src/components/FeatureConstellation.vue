<script setup lang="ts">
import { ref } from 'vue';
import FeatureBubble from './FeatureBubble.vue';
import type { PageKey } from '../types';

defineEmits<{
  navigate: [page: PageKey];
}>();

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

const activeKey = ref<string | null>(null);

const features: FeatureBubbleItem[] = [
  {
    key: 'profile',
    title: '对话画像',
    label: 'Profile',
    description: '和 AI 聊几句，它会记住你的学习目标、学习习惯和还不熟悉的内容。',
    agents: ['ProfileAgent', 'PlannerAgent'],
    scenario: '刚开始使用，或想让推荐更懂你时',
    page: 'profile',
    tone: 'data',
    position: { x: 20, y: 24, size: 120 },
  },
  {
    key: 'resources',
    title: '资源生成',
    label: 'Resources',
    description: '告诉系统你想学什么，就能得到讲义、思维导图、练习题和代码示例。',
    agents: ['KnowledgeAgent', 'LectureAgent', 'QuizAgent', 'SafetyAgent'],
    scenario: '想针对某个知识点快速准备学习材料时',
    page: 'resources',
    tone: 'resource',
    position: { x: 50, y: 13, size: 132 },
  },
  {
    key: 'learningPath',
    title: '学习路径',
    label: 'Path',
    description: '系统会根据你的学习情况，帮你排好先学什么、接着学什么。',
    agents: ['PlannerAgent', 'EvaluationAgent'],
    scenario: '不知道从哪里开始，或想安排学习顺序时',
    page: 'learningPath',
    tone: 'primary',
    position: { x: 31, y: 84, size: 124 },
  },
  {
    key: 'tutor',
    title: '智能辅导',
    label: 'Tutor',
    description: '遇到不会的问题随时提问，AI 会用图、例子或代码一步步讲明白。',
    agents: ['TutorAgent', 'KnowledgeAgent'],
    scenario: '碰到题目、概念或代码看不懂时',
    page: 'tutor',
    tone: 'primary',
    position: { x: 13, y: 58, size: 124 },
  },
  {
    key: 'quiz',
    title: '练习测试',
    label: 'Quiz',
    description: '系统会围绕你的薄弱知识点生成短测，提交后即时判分并分析每道题。',
    agents: ['KnowledgeAgent', 'QuizAgent', 'EvaluationAgent'],
    scenario: '完成辅导后检验掌握情况，或需要一次快速诊断时',
    page: 'quiz',
    tone: 'violet',
    position: { x: 67, y: 84, size: 126 },
  },
  {
    key: 'evaluation',
    title: '学习评估',
    label: 'Insights',
    description: '做完练习后，系统会告诉你哪里学会了、哪里还需要再练一练。',
    agents: ['EvaluationAgent', 'ProfileAgent'],
    scenario: '做完练习，想知道自己掌握得怎么样时',
    page: 'evaluation',
    tone: 'weak',
    position: { x: 87, y: 58, size: 124 },
  },
  {
    key: 'knowledgeBase',
    title: '课程知识库',
    label: 'Library',
    description: '在这里查看和整理课程资料，方便系统给出更准确、更靠谱的回答。',
    agents: ['KnowledgeAgent', 'SafetyAgent'],
    scenario: '想查找课程资料，或了解回答依据时',
    page: 'knowledgeBase',
    tone: 'weak',
    position: { x: 80, y: 24, size: 120 },
  },
];
</script>

<template>
  <section class="feature-constellation" aria-label="功能中心">
    <div class="feature-constellation__map">
      <div class="constellation-core">
        <strong>功能中心</strong>
      </div>
      <div class="constellation-line constellation-line--a"></div>
      <div class="constellation-line constellation-line--b"></div>
      <div class="constellation-line constellation-line--c"></div>

      <FeatureBubble
        v-for="feature in features"
        :key="feature.key"
        :item="feature"
        :active="activeKey === feature.key"
        :dimmed="Boolean(activeKey && activeKey !== feature.key)"
        @hover="activeKey = $event"
        @leave="activeKey = null"
        @enter="feature.page && $emit('navigate', feature.page)"
      />
    </div>
  </section>
</template>
